package nl.pixelretreat.atlas.world;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * File operations on dimension folders. They run only at startup, before Minecraft loads any
 * world, so the source of a copy is never being written at the same time.
 */
public final class WorldFolders {
    /** Files that identify one running world and must never be duplicated by a copy. */
    private static final Set<String> SKIPPED = Set.of("session.lock", "uid.dat");
    private static final String PAPER_IDENTITY = "data/paper/metadata.dat";

    private final Path dimensionsRoot;
    private final int copyThreads;

    /** @param dimensionsRoot {@code <level>/dimensions} */
    public WorldFolders(Path dimensionsRoot, int copyThreads) {
        this.dimensionsRoot = dimensionsRoot.toAbsolutePath().normalize();
        this.copyThreads = copyThreads;
    }

    /** The folder of a dimension key such as {@code atlas:arena}. */
    public Path folder(String key) throws IOException {
        Path folder = dimensionsRoot.resolve(WorldNames.namespace(key)).resolve(WorldNames.shortName(key)).normalize();
        if (!folder.startsWith(dimensionsRoot) || folder.getNameCount() != dimensionsRoot.getNameCount() + 2) {
            throw new IOException("World folder escapes the dimensions folder");
        }
        return folder;
    }

    /** Whether the folder exists. */
    public boolean exists(String key) throws IOException { return Files.isDirectory(folder(key)); }

    /** Deletes an Atlas world's folder. Vanilla dimensions are never deleted. */
    public void delete(String key) throws IOException {
        if (WorldNames.builtIn(key)) throw new IOException("Refusing to delete a vanilla dimension");
        Path folder = folder(key);
        if (Files.exists(folder)) deleteTree(folder);
    }

    /**
     * Replaces a world's folder with a copy of the source. The copy is made next to the target
     * first, so a failed copy leaves the old world in place.
     */
    public void replace(String sourceKey, String targetKey) throws IOException {
        if (WorldNames.builtIn(targetKey)) throw new IOException("Refusing to overwrite a vanilla dimension");
        Path target = folder(targetKey);
        String stagingKey = WorldNames.namespace(targetKey) + ":" + WorldNames.shortName(targetKey) + ".atlas-reset";
        Path staging = folder(stagingKey);
        if (Files.exists(staging)) deleteTree(staging);
        copy(sourceKey, stagingKey);
        delete(targetKey);
        Files.move(staging, target);
    }

    private static void deleteTree(Path folder) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Copies a world folder into a target that does not exist yet. Files are copied in parallel
     * on a bounded pool; the target gets a fresh Paper identity when it first loads.
     */
    public void copy(String sourceKey, String targetKey) throws IOException {
        if (WorldNames.builtIn(targetKey)) throw new IOException("Refusing to overwrite a vanilla dimension");
        Path source = folder(sourceKey);
        Path target = folder(targetKey);
        if (!Files.isDirectory(source)) throw new IOException("Source world folder does not exist");
        if (Files.exists(target)) throw new IOException("Target world folder already exists");
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else if (!SKIPPED.contains(path.getFileName().toString())
                        && !relative.toString().replace('\\', '/').equals(PAPER_IDENTITY)) files.add(path);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(copyThreads, runnable -> {
            var thread = new Thread(runnable, "Atlas-copy");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> copies = new ArrayList<>();
            for (Path file : files) {
                copies.add(pool.submit(() -> {
                    Files.copy(file, target.resolve(source.relativize(file).toString()));
                    return null;
                }));
            }
            for (Future<?> copy : copies) copy.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("World copy was interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("World copy failed", failure.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
