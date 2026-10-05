package nl.pixelretreat.atlas.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import nl.pixelretreat.atlas.api.AtlasFlag;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Required config values are never guessed, and diagnostics never expose the database password. */
class AtlasConfigTest {
    private static final String PASSWORD = "sentinel-password-must-not-leak";

    private static YamlConfiguration shippedConfig() throws Exception {
        try (var input = AtlasConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            yaml.set("database.username", "atlas");
            yaml.set("database.password", PASSWORD);
            return yaml;
        }
    }

    @Test void tradingIsRequiredAndMustBeABoolean() throws Exception {
        assertTrue(AtlasConfig.read(shippedConfig()).defaultFlags().get(AtlasFlag.TRADING),
                "the shipped config turns trading on by default");

        YamlConfiguration missing = shippedConfig();
        missing.set("default-flags.trading", null);
        assertEquals("Missing default-flags.trading",
                assertThrows(IllegalArgumentException.class, () -> AtlasConfig.read(missing)).getMessage());
        assertFalse(missing.isSet("default-flags.trading"), "a failed read must not write a fallback into the config");

        YamlConfiguration wrongType = shippedConfig();
        wrongType.set("default-flags.trading", "yes");
        assertThrows(IllegalArgumentException.class, () -> AtlasConfig.read(wrongType));
    }

    @Test void databasePasswordNeverAppearsInDiagnostics() throws Exception {
        AtlasConfig config = AtlasConfig.read(shippedConfig());
        assertFalse(config.toString().contains(PASSWORD));
        assertTrue(config.toString().contains("databasePassword=***"));
    }
}
