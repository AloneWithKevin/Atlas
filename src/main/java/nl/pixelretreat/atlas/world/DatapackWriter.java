package nl.pixelretreat.atlas.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes the Atlas data pack that declares every enabled Atlas world as a dimension. Minecraft
 * reads data packs before plugins start, so a change becomes active at the next server start.
 */
public final class DatapackWriter {
    private final Path packRoot;
    private final int packFormat;

    /** @param packRoot {@code <level>/datapacks/atlas} */
    public DatapackWriter(Path packRoot, int packFormat) {
        this.packRoot = packRoot;
        this.packFormat = packFormat;
    }

    /** The JSON of a dimension ({@code LevelStem}) for this generator. */
    public static String dimensionJson(WorldGenerator generator) {
        String chunkGenerator = switch (generator) {
            case NORMAL -> noise("minecraft:overworld",
                    "{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:overworld\"}");
            case NETHER -> noise("minecraft:nether",
                    "{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:nether\"}");
            case END -> noise("minecraft:end", "{\"type\":\"minecraft:the_end\"}");
            case FLAT -> flat("minecraft:plains", "[{\"block\":\"minecraft:bedrock\",\"height\":1},"
                    + "{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}]");
            case VOID -> flat("minecraft:the_void", "[]");
        };
        return "{\"type\":\"" + generator.dimensionType() + "\",\"generator\":" + chunkGenerator + "}\n";
    }

    private static String noise(String settings, String biomeSource) {
        return "{\"type\":\"minecraft:noise\",\"settings\":\"" + settings + "\",\"biome_source\":" + biomeSource + "}";
    }

    private static String flat(String biome, String layers) {
        return "{\"type\":\"minecraft:flat\",\"settings\":{\"biome\":\"" + biome + "\",\"layers\":" + layers
                + ",\"lakes\":false,\"features\":false,\"structure_overrides\":[]}}";
    }

    /** The files the pack should contain for these worlds, relative to the pack root. */
    public Map<String, String> expectedFiles(Collection<WorldRecord> worlds) {
        Map<String, String> files = new TreeMap<>();
        files.put("pack.mcmeta", "{\"pack\":{\"description\":\"Atlas worlds\",\"min_format\":" + packFormat
                + ",\"max_format\":" + packFormat + "}}\n");
        for (WorldRecord world : worlds) {
            if (world.builtIn() || !world.enabled() || world.generator().isEmpty()) continue;
            files.put("data/" + WorldNames.NAMESPACE + "/dimension/" + WorldNames.shortName(world.key()) + ".json",
                    dimensionJson(world.generator().get()));
        }
        return files;
    }

    /**
     * Makes the pack on disk match the worlds. Returns whether anything changed, which means the
     * world set differs from what is loaded until the next start.
     */
    public boolean synchronize(Collection<WorldRecord> worlds) throws IOException {
        Map<String, String> expected = expectedFiles(worlds);
        boolean changed = false;
        for (var entry : expected.entrySet()) {
            Path target = resolve(entry.getKey());
            byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), content)) continue;
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            changed |= !entry.getKey().equals("pack.mcmeta");
        }
        Path dimensions = packRoot.resolve("data").resolve(WorldNames.NAMESPACE).resolve("dimension");
        if (Files.isDirectory(dimensions)) {
            Set<String> keep = new HashSet<>(expected.keySet());
            try (var listing = Files.list(dimensions)) {
                for (Path file : listing.toList()) {
                    String relative = packRoot.relativize(file).toString().replace('\\', '/');
                    if (!keep.contains(relative)) {
                        Files.delete(file);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private Path resolve(String relative) throws IOException {
        Path target = packRoot.resolve(relative).normalize();
        if (!target.startsWith(packRoot)) throw new IOException("Path escapes the Atlas data pack");
        return target;
    }
}
