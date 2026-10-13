package nl.pixelretreat.atlas;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import nl.pixelretreat.postbox.api.PostboxBootstrapIdentity;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.junit.jupiter.api.Test;

final class BootstrapIdentityGuardTest {
    @Test
    void allowsMatchingEuAndNaNodes() {
        assertDoesNotThrow(() -> BootstrapIdentityGuard.requireMatch(
            PostboxBootstrapIdentity.fromConfigValue("EU"), "EU"));
        assertDoesNotThrow(() -> BootstrapIdentityGuard.requireMatch(
            PostboxBootstrapIdentity.fromConfigValue("NA"), "NA"));
    }

    @Test
    void refusesMissingBootstrapIdentity() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> BootstrapIdentityGuard.requireMatch(null, "EU"));
        assertEquals("Postbox bootstrap identity is unavailable", failure.getMessage());
    }

    @Test
    void refusesMismatchedBootstrapIdentity() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> BootstrapIdentityGuard.requireMatch(
                PostboxBootstrapIdentity.fromConfigValue("NA"), "EU"));
        assertEquals("Atlas region is EU but Postbox bootstrap identity is NA", failure.getMessage());
    }

    @Test
    void descriptorLoadsAfterStartupPostboxAndDeclaresItAsDependency() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream("/plugin.yml")) {
            assertTrue(resource != null);
            PluginDescriptionFile descriptor = new PluginDescriptionFile(resource);
            assertEquals(PluginLoadOrder.POSTWORLD, descriptor.getLoad());
            assertTrue(descriptor.getDepend().contains("Postbox"));
        }
    }
}
