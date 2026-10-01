package nl.pixelretreat.atlas.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
