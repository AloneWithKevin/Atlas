package nl.pixelretreat.atlas.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldNamesTest {
    @Test void vanillaNamesMapToMinecraftKeys() {
        assertEquals(Optional.of(WorldNames.OVERWORLD), WorldNames.key("overworld"));
        assertEquals(Optional.of(WorldNames.NETHER), WorldNames.key("nether"));
        assertEquals(Optional.of(WorldNames.NETHER), WorldNames.key("minecraft:the_nether"));
        assertEquals(Optional.of(WorldNames.END), WorldNames.key("The_End"));
    }

    @Test void otherNamesBecomeAtlasKeys() {
        assertEquals(Optional.of("atlas:arena_1"), WorldNames.key("Arena_1"));
        assertEquals(Optional.of("atlas:hub"), WorldNames.key("atlas:hub"));
    }

    @Test void invalidAndReservedNamesAreRejected() {
        assertTrue(WorldNames.key("../world").isEmpty());
        assertTrue(WorldNames.key("other:world").isEmpty());
        assertTrue(WorldNames.key("atlas:overworld").isEmpty());
        assertTrue(WorldNames.key("a".repeat(49)).isEmpty());
        assertFalse(WorldNames.isNewWorldName("nether"));
        assertTrue(WorldNames.isNewWorldName("spawn"));
    }

    @Test void shortNamesAndNamespaces() {
        assertEquals("arena", WorldNames.shortName("atlas:arena"));
        assertEquals("atlas", WorldNames.namespace("atlas:arena"));
        assertTrue(WorldNames.builtIn(WorldNames.END));
        assertFalse(WorldNames.builtIn("atlas:end_copy"));
    }
}
