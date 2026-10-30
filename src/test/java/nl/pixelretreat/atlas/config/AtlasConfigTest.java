package nl.pixelretreat.atlas.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import nl.pixelretreat.atlas.api.AtlasFlag;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Required config values are never guessed, and diagnostics never expose the database password. */
class AtlasConfigTest {
    private static final String HOST = "sentinel-db-host.invalid";
    private static final String NAME = "sentinel-database-name";
    private static final String USERNAME = "sentinel-database-user";
    private static final String PASSWORD = "sentinel-password-must-not-leak";

    private static YamlConfiguration shippedConfig() throws Exception {
        try (var input = AtlasConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
            yaml.set("database.host", HOST);
            yaml.set("database.name", NAME);
            yaml.set("database.username", USERNAME);
            yaml.set("database.password", PASSWORD);
            return yaml;
        }
    }

    @Test void exactServerIdentitiesAndArbitraryConfiguredRoutesAreAccepted() throws Exception {
        var yaml=shippedConfig();var hub=AtlasConfig.read(yaml);
        assertEquals("Hub01",hub.serverId());assertEquals(java.util.List.of("Survival01"),hub.remoteServers());
        yaml.set("server.proxy-names.Survival02","survival-two");
        var expanded=AtlasConfig.read(yaml);
        assertEquals(java.util.List.of("Survival01","Survival02"),expanded.remoteServers());
        assertTrue(expanded.routesTo("Survival02"));assertFalse(expanded.routesTo("survival02"));
        assertFalse(expanded.routesTo("Hub01"));assertFalse(expanded.routesTo("unconfigured"));
        yaml.set("server.id","Survival02");assertEquals("Survival02",AtlasConfig.read(yaml).serverId());
        yaml.set("server.id","missing");assertThrows(IllegalArgumentException.class,()->AtlasConfig.read(yaml));
        yaml.set("server.id","bad space");assertThrows(IllegalArgumentException.class,()->AtlasConfig.read(yaml));
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

    @Test void databaseConnectionDetailsNeverAppearInDiagnostics() throws Exception {
        String diagnostic = AtlasConfig.read(shippedConfig()).toString();
        assertFalse(diagnostic.contains(HOST));
        assertFalse(diagnostic.contains(NAME));
        assertFalse(diagnostic.contains(USERNAME));
        assertFalse(diagnostic.contains(PASSWORD));
        assertTrue(diagnostic.contains("database=***"));
    }
}
