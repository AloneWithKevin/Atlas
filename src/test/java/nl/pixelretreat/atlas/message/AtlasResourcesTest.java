package nl.pixelretreat.atlas.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import dev.veyra.api.VeyraCluster;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.campfire.catalog.CatalogLifetime;
import nl.pixelretreat.campfire.catalog.LoadedMessageCatalog;
import nl.pixelretreat.campfire.catalog.MessageFileLoader;
import nl.pixelretreat.campfire.api.CampfireLocalization;
import nl.pixelretreat.campfire.api.CampfireRuntime;
import nl.pixelretreat.campfire.localization.DefaultCampfireLocalization;
import nl.pixelretreat.closet.config.ContributionYaml;
import nl.pixelretreat.closet.service.ContributionValidator;
import nl.pixelretreat.closet.service.DefaultClosetService;
import nl.pixelretreat.closet.api.ContentContribution;
import nl.pixelretreat.closet.api.ItemId;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shipped resources must satisfy the real Campfire, Closet and config contracts. */
class AtlasResourcesTest {
    @TempDir Path directory;

    @Test void shippedCatalogSatisfiesTheMessageContract() throws Exception {
        var path = Path.of(getClass().getResource("/messages.yml").toURI());
        var templates = new MessageFileLoader().load("Atlas", path);
        AtlasMessages.validateCatalog(new LoadedMessageCatalog("Atlas", templates, new CatalogLifetime()));
    }

    @Test void shippedSelectorRegistersAgainstRealCampfireAndCloset() throws Exception {
        for (String resource : java.util.List.of("messages.yml", "closet.yml")) {
            try (var input = getClass().getResourceAsStream("/" + resource)) {
                assertNotNull(input);
                Files.copy(input, directory.resolve(resource));
            }
        }
        var owner = mock(Plugin.class);
        when(owner.getName()).thenReturn("Atlas");
        when(owner.getDataFolder()).thenReturn(directory.toFile());
        when(owner.isEnabled()).thenReturn(true);
        var material = mock(Material.class);
        when(material.isItem()).thenReturn(true);
        when(material.getMaxStackSize()).thenReturn(1);
        try (var materials = mockStatic(Material.class);
             var localization = new DefaultCampfireLocalization(Runnable::run)) {
            materials.when(() -> Material.valueOf("WOODEN_AXE")).thenReturn(material);
            var catalog = localization.register(owner).toCompletableFuture().get();
            var contribution = new ContributionYaml().load(directory);
            assertEquals(1, contribution.items().size());
            assertEquals(directory.resolve("content"), contribution.assetsRoot());
            // External authoring content is read directly, never copied into build output.
            Path ownerRoot = Path.of(".").toAbsolutePath().normalize();
            var external = new ContentContribution(contribution.items(), contribution.glyphs(), ownerRoot.resolve("content"));
            var assets = new ContributionValidator().validate(external, ownerRoot, catalog);
            assertEquals(Set.of("assets/atlas/font/lore.json",
                    "assets/pixelretreat/textures/gui/atlas_lore/action.png"), assets.sha256().keySet());
            var localizationApi = mock(CampfireLocalization.class);
            when(localizationApi.register(owner)).thenReturn(CompletableFuture.completedFuture(catalog));
            var runtime = mock(CampfireRuntime.class);
            when(runtime.localization()).thenReturn(localizationApi);
            when(owner.getDataFolder()).thenReturn(ownerRoot.toFile());
            var provider = mock(Plugin.class);
            when(provider.isEnabled()).thenReturn(true);
            try (var worker = Executors.newSingleThreadExecutor();
                 var service = new DefaultClosetService(provider, mock(VeyraCluster.class), runtime, worker)) {
                assertTrue(service.register(owner, external).toCompletableFuture().get(5, TimeUnit.SECONDS).active());
                assertTrue(service.catalog().items().containsKey(ItemId.parse("atlas:selector")));
                assertEquals(1, service.catalog().glyphs().size());
                assertEquals(Key.key("atlas:lore"), service.glyph(ItemId.parse("atlas:lore/action")).font());
                assertEquals("\uE000", PlainTextComponentSerializer.plainText().serialize(
                        service.glyph(ItemId.parse("atlas:lore/action"))));
                assertEquals(assets.sha256(), service.packInputs().get("atlas").sha256());
            }
        }
    }

    @Test void shippedConfigIsCompleteApartFromCredentials() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            var yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("", yaml.getString("database.password"));
            yaml.set("database.username", "atlas");
            AtlasConfig config = AtlasConfig.read(yaml);
            assertEquals("EU", config.region());
            assertEquals("NA", config.otherRegion());
            assertEquals(AtlasFlag.values().length, config.defaultFlags().size());
            assertFalse(config.defaultFlags().get(AtlasFlag.KEEP_INVENTORY));
            assertEquals(121, config.packFormat());
            yaml.set("default-flags.portals", null);
            assertThrows(IllegalArgumentException.class, () -> AtlasConfig.read(yaml));
        }
    }

    @Test void everyMessageUsesItsContextPaletteAndKeepsInputLiteral() throws Exception {
        var catalog = catalog();
        String input = "<red>input</red><click:run_command:'/op injected'>literal</click>";
        for (var entry : AtlasMessages.CONTRACT.entrySet()) {
            var parameters = entry.getValue().stream().collect(Collectors.toMap(name -> name, name -> input));
            Component rendered = catalog.get(entry.getKey(), parameters);
            String plain = PlainTextComponentSerializer.plainText().serialize(rendered);
            assertEquals(entry.getValue().size(), plain.split(java.util.regex.Pattern.quote(input), -1).length - 1,
                    entry.getKey() + " must preserve every plain input value");
            Set<TextColor> palette = entry.getKey().startsWith("items.")
                    ? Set.of(TextColor.color(0xFFFFFF), TextColor.color(0xF3E5AB), TextColor.color(0xA8D5A2))
                    : Set.of(TextColor.color(0xF3E5AB), TextColor.color(0xA8D5A2));
            checkPalette(rendered, palette, entry.getKey());
        }
    }

    @Test void labelsActionsAndSelectorValuesHaveDistinctColorsWithoutBleeding() throws Exception {
        var catalog = catalog();
        var usage = catalog.get("usage.tp");
        assertColor(usage, "Usage:", TextColor.color(0xF3E5AB));
        assertColor(usage, "/atlas tp {world} [player]", TextColor.color(0xA8D5A2));
        var info = catalog.get("info.state", Map.of("loaded", "LOADED", "enabled", "ENABLED",
                "generator", "GENERATOR", "players", "PLAYERS"));
        for (String label : List.of("Loaded:", "Enabled:", "Generator:", "Players:")) {
            assertColor(info, label, TextColor.color(0xF3E5AB));
        }
        for (String value : List.of("LOADED", "ENABLED", "GENERATOR", "PLAYERS", " · ")) {
            assertColor(info, value, null); // Campfire owns ordinary chat's default color.
        }
        assertColor(catalog.get("items.selector.name"), "Atlas Selector", TextColor.color(0xFFFFFF));
        var lore = catalog.get("items.selector.lore");
        for (String action : List.of("Left-click: pick", "Right-click: pick")) {
            assertColor(lore, action, TextColor.color(0xA8D5A2));
        }
        for (String value : List.of("corner 1", "corner 2")) {
            assertColor(lore, value, TextColor.color(0xFFFFFF));
        }
        assertEquals("\uE000 Left-click: pick corner 1  Right-click: pick corner 2",
                PlainTextComponentSerializer.plainText().serialize(lore));
    }

    @Test void selectorGlyphUsesExternalBitmapAndDoesNotChangeTextFont() throws Exception {
        Path content = Path.of("content");
        var image = ImageIO.read(content.resolve("assets/pixelretreat/textures/gui/atlas_lore/action.png").toFile());
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertTrue(image.getColorModel().hasAlpha());
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        var font = new org.yaml.snakeyaml.Yaml().loadAs(Files.readString(content.resolve("assets/atlas/font/lore.json")), Map.class);
        var bitmap = (Map<?, ?>) ((List<?>) font.get("providers")).getFirst();
        assertEquals("bitmap", bitmap.get("type"));
        assertEquals("pixelretreat:gui/atlas_lore/action.png", bitmap.get("file"));
        assertEquals(8, bitmap.get("height"));
        assertEquals(7, bitmap.get("ascent"));
        assertEquals(List.of("\uE000"), bitmap.get("chars"));
        var lore = catalog().get("items.selector.lore");
        assertColor(lore, "\uE000", TextColor.color(0xFFFFFF));
        assertFonts(lore, null);
    }

    private void assertFonts(Component component, Key inherited) {
        Key font = component.font() == null ? inherited : component.font();
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            assertEquals(text.content().equals("\uE000") ? Key.key("atlas:lore") : null, font,
                    "The custom font must stay confined to the glyph");
        }
        for (Component child : component.children()) assertFonts(child, font);
    }

    private LoadedMessageCatalog catalog() throws Exception {
        var path = Path.of(getClass().getResource("/messages.yml").toURI());
        return new LoadedMessageCatalog("Atlas", new MessageFileLoader().load("Atlas", path), new CatalogLifetime());
    }

    private void checkPalette(Component component, Set<TextColor> palette, String key) {
        assertTrue(component.color() == null || palette.contains(component.color()), key + " has a foreign color");
        assertNull(component.clickEvent(), key + " parsed a click event");
        assertNull(component.hoverEvent(), key + " parsed a hover event");
        for (Component child : component.children()) checkPalette(child, palette, key);
    }

    private void assertColor(Component component, String text, TextColor expected) {
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        int start = plain.indexOf(text);
        assertTrue(start >= 0, "Missing text: " + text);
        var colors = new ArrayList<TextColor>();
        collectColors(component, null, colors);
        for (int index = start; index < start + text.length(); index++) {
            assertEquals(expected, colors.get(index), text + " at character " + index);
        }
    }

    private void collectColors(Component component, TextColor inherited, List<TextColor> colors) {
        TextColor effective = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text) {
            for (int index = 0; index < text.content().length(); index++) colors.add(effective);
        }
        for (Component child : component.children()) collectColors(child, effective, colors);
    }
}
