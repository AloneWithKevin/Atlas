package nl.pixelretreat.atlas.portal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import nl.pixelretreat.atlas.keeploaded.KeepLoadedRegion;
import nl.pixelretreat.atlas.world.SpawnPoint;
import org.junit.jupiter.api.Test;

class PortalIndexTest {
    private static Portal portal(String name, int minX, int minZ, int maxX, int maxZ) {
        return new Portal(name, "minecraft:overworld", minX, 60, minZ, maxX, 63, maxZ, PortalTarget.spawn("atlas:arena"),
                2500, Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    @Test void findsPortalsAcrossChunkBordersAndNegativeCoordinates() {
        var index = new PortalIndex(List.of(portal("gate", -2, 14, 1, 17), portal("far", 1000, 1000, 1001, 1001)));
        assertEquals("gate", index.at("minecraft:overworld", -2, 60, 14).orElseThrow().name());
        assertEquals("gate", index.at("minecraft:overworld", 1, 63, 17).orElseThrow().name());
        assertTrue(index.at("minecraft:overworld", 1, 64, 17).isEmpty());
        assertTrue(index.at("minecraft:the_nether", 0, 60, 15).isEmpty());
        assertTrue(index.anyInChunk("minecraft:overworld", -1, 0));
        assertTrue(index.anyInChunk("minecraft:overworld", 0, 1));
        assertFalse(index.anyInChunk("minecraft:overworld", 5, 5));
        assertTrue(index.anyIn("minecraft:overworld"));
        assertEquals("far", index.named("far").orElseThrow().name());
    }

    @Test void targetsValidateTheirShape() {
        var point = Optional.of(new SpawnPoint(0, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PortalTarget(PortalTarget.Kind.SERVER, Optional.empty(), "minecraft:overworld", point));
        assertThrows(IllegalArgumentException.class,
                () -> new PortalTarget(PortalTarget.Kind.LOCATION, Optional.empty(), "minecraft:overworld", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new PortalTarget(PortalTarget.Kind.SPAWN, Optional.of("NA"), "minecraft:overworld", Optional.empty()));
        assertEquals(PortalTarget.Kind.SERVER,
                new PortalTarget(PortalTarget.Kind.SERVER, Optional.of("NA"), "atlas:hub", Optional.empty()).kind());
    }

    @Test void volumeAndPermission() {
        Portal gate = portal("gate", 0, 0, 2, 0);
        assertEquals(12, gate.volume());
        assertEquals("atlas.portal.gate", gate.permission());
    }

    @Test void keepLoadedRegionsUseFloorDivisionAndCountWithoutOverflow() {
        assertEquals(1, KeepLoadedRegion.fromBlocks("hub", "w", 0, 0, 15, 15).chunkCount());
        assertEquals(4, KeepLoadedRegion.fromBlocks("hub", "w", -1, -1, 0, 0).chunkCount());
        var hub = KeepLoadedRegion.fromBlocks("hub", "w", -274, 532, 43, 902);
        assertEquals(-18, hub.minChunkX());
        assertEquals(2, hub.maxChunkX());
        assertEquals(504, hub.chunkCount());
        var huge = KeepLoadedRegion.fromBlocks("x", "w", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(huge.chunkCount() > Integer.MAX_VALUE);
    }
}
