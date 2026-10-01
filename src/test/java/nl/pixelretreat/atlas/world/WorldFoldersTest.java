package nl.pixelretreat.atlas.world;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFoldersTest {
    @TempDir Path dimensions;

    private void world(String namespace, String name, String content) throws IOException {
        Path folder = dimensions.resolve(namespace).resolve(name);
        Files.createDirectories(folder.resolve("region"));
        Files.createDirectories(folder.resolve("data/paper"));
        Files.writeString(folder.resolve("region/r.0.0.mca"), content);
        Files.writeString(folder.resolve("session.lock"), "lock");
        Files.writeString(folder.resolve("data/paper/metadata.dat"), "uuid");
        Files.writeString(folder.resolve("data/paper/level_overrides.dat"), "overrides");
    }

    @Test void copySkipsLocksAndTheWorldIdentity() throws Exception {
        world("atlas", "template", "chunks");
        var folders = new WorldFolders(dimensions, 2);
        folders.copy("atlas:template", "atlas:copy");
        Path copy = dimensions.resolve("atlas/copy");
        assertEquals("chunks", Files.readString(copy.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(copy.resolve("data/paper/level_overrides.dat")));
        assertFalse(Files.exists(copy.resolve("session.lock")));
        assertFalse(Files.exists(copy.resolve("data/paper/metadata.dat")));
        assertThrows(IOException.class, () -> folders.copy("atlas:template", "atlas:copy"));
    }

    @Test void vanillaDimensionsCanBeCopiedButNeverOverwrittenOrDeleted() throws Exception {
        world("minecraft", "overworld", "vanilla");
        var folders = new WorldFolders(dimensions, 1);
        folders.copy(WorldNames.OVERWORLD, "atlas:overworld_copy");
        assertTrue(folders.exists("atlas:overworld_copy"));
        assertThrows(IOException.class, () -> folders.delete(WorldNames.OVERWORLD));
        assertThrows(IOException.class, () -> folders.replace("atlas:overworld_copy", WorldNames.OVERWORLD));
    }

    @Test void replaceSwapsInAFreshCopyAndDeleteRemovesTheFolder() throws Exception {
        world("atlas", "template", "fresh");
        world("atlas", "arena", "played");
        var folders = new WorldFolders(dimensions, 2);
        folders.replace("atlas:template", "atlas:arena");
        assertEquals("fresh", Files.readString(dimensions.resolve("atlas/arena/region/r.0.0.mca")));
        assertFalse(Files.exists(dimensions.resolve("atlas/arena.atlas-reset")));
        folders.delete("atlas:arena");
        assertFalse(folders.exists("atlas:arena"));
    }

    @Test void aMissingSourceFailsWithoutCreatingTheTarget() {
        var folders = new WorldFolders(dimensions, 1);
        assertThrows(IOException.class, () -> folders.copy("atlas:missing", "atlas:target"));
        assertFalse(Files.exists(dimensions.resolve("atlas/target")));
    }
}
