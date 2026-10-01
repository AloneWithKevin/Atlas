package nl.pixelretreat.atlas.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatapackWriterTest {
    @TempDir Path directory;

    private static WorldRecord atlasWorld(String name, WorldGenerator generator, boolean enabled) {
        return new WorldRecord("atlas:" + name, Optional.of(generator), enabled, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    @Test void voidUsesAnEmptyFlatGeneratorInTheVoidBiome() {
        String json = DatapackWriter.dimensionJson(WorldGenerator.VOID);
        assertTrue(json.startsWith("{\"type\":\"minecraft:overworld\",\"generator\":{\"type\":\"minecraft:flat\""));
        assertTrue(json.contains("\"biome\":\"minecraft:the_void\""));
        assertTrue(json.contains("\"layers\":[]"));
        assertTrue(json.contains("\"structure_overrides\":[]"));
    }

    @Test void netherAndEndUseTheirOwnDimensionTypes() {
        assertTrue(DatapackWriter.dimensionJson(WorldGenerator.NETHER).contains("\"type\":\"minecraft:the_nether\""));
        assertTrue(DatapackWriter.dimensionJson(WorldGenerator.NETHER).contains("\"preset\":\"minecraft:nether\""));
        assertTrue(DatapackWriter.dimensionJson(WorldGenerator.END).contains("\"settings\":\"minecraft:end\""));
        assertTrue(DatapackWriter.dimensionJson(WorldGenerator.NORMAL).contains("\"preset\":\"minecraft:overworld\""));
    }

    @Test void onlyEnabledAtlasWorldsAreDeclared() {
        var writer = new DatapackWriter(directory, 121);
        var files = writer.expectedFiles(List.of(atlasWorld("arena", WorldGenerator.VOID, true),
                atlasWorld("old", WorldGenerator.FLAT, false), WorldRecord.builtIn(WorldNames.OVERWORLD)));
        assertEquals(List.of("data/atlas/dimension/arena.json", "pack.mcmeta"), List.copyOf(files.keySet()));
        assertTrue(files.get("pack.mcmeta").contains("\"min_format\":121"));
    }

    @Test void synchronizeAddsAndRemovesDimensionsAndReportsChanges() throws Exception {
        var writer = new DatapackWriter(directory, 121);
        assertTrue(writer.synchronize(List.of(atlasWorld("arena", WorldGenerator.VOID, true))));
        assertTrue(Files.isRegularFile(directory.resolve("data/atlas/dimension/arena.json")));
        assertFalse(writer.synchronize(List.of(atlasWorld("arena", WorldGenerator.VOID, true))));
        assertTrue(writer.synchronize(List.of(atlasWorld("arena", WorldGenerator.VOID, false))));
        assertFalse(Files.exists(directory.resolve("data/atlas/dimension/arena.json")));
        assertTrue(Files.isRegularFile(directory.resolve("pack.mcmeta")));
    }
}
