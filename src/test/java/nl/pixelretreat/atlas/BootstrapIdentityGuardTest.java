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
    static PostboxBootstrapIdentity identity(String value) {
        var id=new nl.pixelretreat.postbox.api.ServerId(value);
        return new PostboxBootstrapIdentity(new nl.pixelretreat.postbox.api.NetworkTopology(id,java.util.Map.of(id,
            new nl.pixelretreat.postbox.api.ServerDefinition(id,java.util.Optional.of("EU"),true,true))));
    }
    @Test
    void allowsMatchingEuAndNaNodes() {
        assertDoesNotThrow(() -> BootstrapIdentityGuard.requireMatch(
            identity("EU"), "EU"));
        assertDoesNotThrow(() -> BootstrapIdentityGuard.requireMatch(
            identity("NA"), "NA"));
    }

    @Test void exactSameRegionServerIdsAreIndependent() {
        assertDoesNotThrow(()->BootstrapIdentityGuard.requireMatch(identity("Survival01"),"Survival01"));
        assertDoesNotThrow(()->BootstrapIdentityGuard.requireMatch(identity("Survival02"),"Survival02"));
        assertThrows(IllegalStateException.class,()->BootstrapIdentityGuard.requireMatch(identity("Survival01"),"Survival02"));
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
                identity("NA"), "EU"));
        assertEquals("Atlas server is EU but Postbox bootstrap identity is NA", failure.getMessage());
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
