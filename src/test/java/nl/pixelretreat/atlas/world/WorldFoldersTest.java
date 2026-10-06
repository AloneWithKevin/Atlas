package nl.pixelretreat.atlas.world;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        var vanillaTarget = new PendingOperation(1, PendingOperation.Kind.RESET, WorldNames.OVERWORLD,
                java.util.Optional.of("atlas:overworld_copy"), java.util.UUID.randomUUID(), java.time.Instant.now());
        assertThrows(IOException.class, () -> folders.prepareCopy(vanillaTarget, ignored -> { }));
    }

    @Test void preparedCopyCanPublishWhileRetainingTheOriginalTree() throws Exception {
        world("atlas", "template", "fresh");
        world("atlas", "arena", "played");
        var folders = new WorldFolders(dimensions, 2);
        var operation = new PendingOperation(2, PendingOperation.Kind.RESET, "atlas:arena",
                java.util.Optional.of("atlas:template"), java.util.UUID.randomUUID(), java.time.Instant.now());
        var oldEntries = new java.util.HashMap<String, WorldCopyState.Entry>();
        var newEntries = new java.util.HashMap<String, WorldCopyState.Entry>();
        long originals = folders.recordTree(folders.folder("atlas:arena"), entry -> oldEntries.put(entry.path(), entry));
        long prepared = folders.prepareCopy(operation, entry -> newEntries.put(entry.path(), entry));
        folders.verifyTree(folders.folder("atlas:arena"), originals, path -> java.util.Optional.ofNullable(oldEntries.get(path)));
        folders.verifyTree(folders.staging(operation), prepared, path -> java.util.Optional.ofNullable(newEntries.get(path)));
        folders.moveTree(folders.folder("atlas:arena"), folders.backup(operation));
        folders.moveTree(folders.staging(operation), folders.folder("atlas:arena"));
        assertEquals("fresh", Files.readString(dimensions.resolve("atlas/arena/region/r.0.0.mca")));
        assertEquals("played", Files.readString(folders.backup(operation).resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(folders.staging(operation)));
        folders.delete("atlas:arena");
        assertFalse(folders.exists("atlas:arena"));
    }

    @Test void aMissingSourceFailsWithoutCreatingTheTarget() {
        var folders = new WorldFolders(dimensions, 1);
        assertThrows(IOException.class, () -> folders.copy("atlas:missing", "atlas:target"));
        assertFalse(Files.exists(dimensions.resolve("atlas/target")));
    }

    @Test void traversalWaitsForTheCopyBudgetInsteadOfMaterializingTheWholeWorld() throws Exception {
        Path source = dimensions.resolve("atlas/template");
        for (int i = 0; i < 100; i++) {
            Path folder = source.resolve("part_" + i);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("chunk"), "chunk_" + i);
        }
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var folders = new WorldFolders(dimensions, 2, (from, to) -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            Files.copy(from, to);
        });
        try (var caller = Executors.newSingleThreadExecutor()) {
            var copying = caller.submit(() -> { folders.copy("atlas:template", "atlas:copy"); return null; });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                try (var targets = Files.list(dimensions.resolve("atlas/copy"))) {
                    assertTrue(targets.count() <= 3, "Traversal must wait before preparing the entire tree");
                }
                assertFalse(copying.isDone());
            } finally { release.countDown(); }
            copying.get(5, TimeUnit.SECONDS);
        }
        try (var files = Files.walk(dimensions.resolve("atlas/copy"))) {
            assertEquals(100, files.filter(Files::isRegularFile).count());
        }
    }

    @Test void failureDoesNotReturnWhileAnInterruptedWriterIsStillRetiring() throws Exception {
        world("atlas", "template", "chunks");
        var writerStarted = new CountDownLatch(1);
        var writerInterrupted = new CountDownLatch(1);
        var allowRetire = new CountDownLatch(1);
        var entered = new AtomicInteger();
        var active = new AtomicInteger();
        var folders = new WorldFolders(dimensions, 2, (from, to) -> {
            active.incrementAndGet();
            try {
                if (entered.incrementAndGet() == 1) {
                    try { writerStarted.await(); }
                    catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                    throw new IOException("injected copy failure");
                }
                writerStarted.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) {
                    writerInterrupted.countDown();
                    boolean retired = false;
                    while (!retired) {
                        try { allowRetire.await(); retired = true; }
                        catch (InterruptedException ignored) { }
                    }
                }
            } finally { active.decrementAndGet(); }
        });
        try (var caller = Executors.newSingleThreadExecutor()) {
            var copying = caller.submit(() -> assertThrows(IOException.class,
                    () -> folders.copy("atlas:template", "atlas:copy")));
            try {
                assertTrue(writerInterrupted.await(5, TimeUnit.SECONDS));
                assertFalse(copying.isDone(), "No world may load while a retiring worker can still write");
            } finally { allowRetire.countDown(); }
            copying.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, active.get());
    }

    @Test void failedPreparationLeavesTheOriginalResetWorldIntact() throws Exception {
        world("atlas", "template", "fresh");
        world("atlas", "arena", "old-world");
        var folders = new WorldFolders(dimensions, 2, (from, to) -> {
            throw new IOException("injected disk failure");
        });
        var operation = new PendingOperation(3, PendingOperation.Kind.RESET, "atlas:arena",
                java.util.Optional.of("atlas:template"), java.util.UUID.randomUUID(), java.time.Instant.now());
        assertThrows(IOException.class, () -> folders.prepareCopy(operation, ignored -> { }));
        assertEquals("old-world", Files.readString(dimensions.resolve("atlas/arena/region/r.0.0.mca")));
    }
}
