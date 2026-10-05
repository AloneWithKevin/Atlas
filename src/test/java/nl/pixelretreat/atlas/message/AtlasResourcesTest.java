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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.campfire.catalog.CatalogLifetime;
import nl.pixelretreat.campfire.catalog.LoadedMessageCatalog;
import nl.pixelretreat.campfire.catalog.MessageFileLoader;
import nl.pixelretreat.campfire.localization.DefaultCampfireLocalization;
import nl.pixelretreat.closet.config.ContributionYaml;
import nl.pixelretreat.closet.service.ContributionValidator;
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
            new ContributionValidator().validate(contribution, directory, catalog);
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
        for (String action : List.of("Left-click:", "Right-click:")) {
            assertColor(lore, action, TextColor.color(0xA8D5A2));
        }
        for (String value : List.of("corner 1", "corner 2")) {
            assertColor(lore, value, TextColor.color(0xFFFFFF));
        }
        assertEquals("Left-click: corner 1  Right-click: corner 2",
                PlainTextComponentSerializer.plainText().serialize(lore));
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
