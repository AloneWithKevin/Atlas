package nl.pixelretreat.atlas.world;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

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
    private final FileCopy fileCopy;

    @FunctionalInterface interface FileCopy {
        void copy(Path source, Path target) throws IOException;
    }

    /** Streams evidence to durable storage without collecting the tree in memory. */
    @FunctionalInterface public interface EntrySink {
        void accept(WorldCopyState.Entry entry) throws IOException;
    }

    /** Looks up one immutable entry from durable storage. */
    @FunctionalInterface public interface EntryLookup {
        java.util.Optional<WorldCopyState.Entry> find(String relative) throws IOException;
    }

    /** Evidence is missing or contradictory; no automatic filesystem repair is permitted. */
    public static final class UncertainCopyException extends IOException {
        public UncertainCopyException(String reason) { super(reason); }
    }

    /** @param dimensionsRoot {@code <level>/dimensions} */
    public WorldFolders(Path dimensionsRoot, int copyThreads) {
        this(dimensionsRoot, copyThreads, (source, target) -> Files.copy(source, target));
    }

    WorldFolders(Path dimensionsRoot, int copyThreads, FileCopy fileCopy) {
        this.dimensionsRoot = dimensionsRoot.toAbsolutePath().normalize();
        if (copyThreads < 1) throw new IllegalArgumentException("Copy worker count must be positive");
        this.copyThreads = copyThreads;
        this.fileCopy = java.util.Objects.requireNonNull(fileCopy);
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

    /** Unique staging tree; its contents are never a registered dimension. */
    public Path staging(PendingOperation operation) throws IOException { return workPath(operation, "staging"); }

    /** Original tree is retained here after a proven reset; no retention policy is guessed. */
    public Path backup(PendingOperation operation) throws IOException { return workPath(operation, "original"); }

    private Path workPath(PendingOperation operation, String kind) throws IOException {
        if (WorldNames.builtIn(operation.world()) || operation.id() < 1)
            throw new IOException("Invalid Atlas copy operation");
        Path target = folder(operation.world());
        return target.resolveSibling("." + target.getFileName() + ".atlas-" + operation.id() + "-" + kind);
    }

    /** Flushes copied bytes and records exact prepared entries; all writers retire before returning. */
    public long prepareCopy(PendingOperation operation, EntrySink sink) throws IOException {
        return copyTree(folder(operation.source().orElseThrow()), staging(operation), sink);
    }

    /** Captures an original tree, including identity files, while no world is ticking. */
    public long recordTree(Path root, EntrySink sink) throws IOException {
        checkPath(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new UncertainCopyException("Expected world tree is missing");
        AtomicLong count = new AtomicLong();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                interrupted();
                sink.accept(new WorldCopyState.Entry(relative(root, directory), true, 0, ""));
                count.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                interrupted();
                if (attributes.isSymbolicLink() || !attributes.isRegularFile())
                    throw new UncertainCopyException("World evidence contains a non-regular file");
                sink.accept(new WorldCopyState.Entry(relative(root, file), false, Files.size(file), digest(file)));
                count.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
        return count.get();
    }

    /** Proves every expected entry exists and no extra entry was added. */
    public void verifyTree(Path root, long expectedCount, EntryLookup lookup) throws IOException {
        if (expectedCount < 1) throw new UncertainCopyException("World evidence is incomplete");
        long count = recordTree(root, actual -> {
            WorldCopyState.Entry expected = lookup.find(actual.path()).orElseThrow(
                    () -> new UncertainCopyException("World evidence entry missing"));
            if (!expected.equals(actual)) throw new UncertainCopyException("World evidence no longer matches");
        });
        if (count != expectedCount) throw new UncertainCopyException("World evidence count no longer matches");
    }

    /** Same-parent atomic publication only; never replaces an existing tree or follows a link. */
    public void moveTree(Path from, Path to) throws IOException {
        checkPath(from);
        checkPath(to);
        if (!from.getParent().equals(to.getParent()) || !Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(to, LinkOption.NOFOLLOW_LINKS))
            throw new UncertainCopyException("World publication paths conflict");
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    private void checkPath(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(dimensionsRoot)) throw new IOException("World path escapes the dimensions folder");
        for (Path item = normalized; item != null && item.startsWith(dimensionsRoot); item = item.getParent())
            if (Files.isSymbolicLink(item)) throw new UncertainCopyException("World path contains a symbolic link");
    }

    private static String relative(Path root, Path path) {
        String name = root.relativize(path).toString().replace('\\', '/');
        return name.isEmpty() ? "." : name;
    }

    private static String digest(Path path) throws IOException {
        try {
            var sha = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(path), sha)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
        copyTree(folder(sourceKey), folder(targetKey), null);
    }

    private long copyTree(Path source, Path target, EntrySink sink) throws IOException {
        checkPath(source);
        checkPath(target);
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source world folder does not exist");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new UncertainCopyException("Target world folder already exists");
        AtomicLong count = new AtomicLong();
        ExecutorService pool = new ThreadPoolExecutor(copyThreads, copyThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(copyThreads), runnable -> {
            var thread = new Thread(runnable, "Atlas-copy");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        var completions = new ExecutorCompletionService<Void>(pool);
        Set<Future<Void>> pending = new HashSet<>();
        // close() waits for every writer before returning. This is startup-only work.
        try (pool) {
            try {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                            throws IOException {
                        interrupted();
                        Files.createDirectories(target.resolve(source.relativize(directory).toString()));
                        if (sink != null) {
                            sink.accept(new WorldCopyState.Entry(relative(source, directory), true, 0, ""));
                            count.incrementAndGet();
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        interrupted();
                        if (attributes.isSymbolicLink() || !attributes.isRegularFile())
                            throw new IOException("World copy contains a non-regular file");
                        Path relative = source.relativize(file);
                        if (SKIPPED.contains(file.getFileName().toString())
                                || relative.toString().replace('\\', '/').equals(PAPER_IDENTITY))
                            return FileVisitResult.CONTINUE;
                        if (pending.size() == copyThreads) awaitCopy(completions, pending);
                        pending.add(completions.submit(() -> {
                            interrupted();
                            fileCopy.copy(file, target.resolve(relative.toString()));
                            if (sink != null) {
                                Path copied = target.resolve(relative.toString());
                                try (FileChannel channel = FileChannel.open(copied, StandardOpenOption.WRITE)) {
                                    channel.force(true);
                                }
                                String expected = digest(file);
                                String actual = digest(copied);
                                if (!expected.equals(actual) || Files.size(file) != Files.size(copied))
                                    throw new UncertainCopyException("Copied bytes differ from the source");
                                sink.accept(new WorldCopyState.Entry(relative(source, file), false, Files.size(copied), actual));
                                count.incrementAndGet();
                            }
                            return null;
                        }));
                        return FileVisitResult.CONTINUE;
                    }
                });
                while (!pending.isEmpty()) awaitCopy(completions, pending);
            } finally {
                for (Future<Void> task : pending) task.cancel(true);
                pool.shutdownNow();
            }
        }
        return count.get();
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("World copy was interrupted");
    }

    private static void awaitCopy(ExecutorCompletionService<Void> completions, Set<Future<Void>> pending)
            throws IOException {
        try {
            Future<Void> completed = completions.take();
            pending.remove(completed);
            completed.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("World copy was interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("World copy failed", failure.getCause());
        }
    }
}
