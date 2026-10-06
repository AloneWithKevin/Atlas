package nl.pixelretreat.atlas.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.WorldCopyState;
import nl.pixelretreat.atlas.world.WorldFolders;

/** Startup-only publication; every resumed move requires positive durable tree evidence. */
public final class WorldCopyRecovery {
    enum Boundary { PREPARED, OLD_MOVED, NEW_MOVED, PUBLISHED }
    @FunctionalInterface interface Checkpoint { void reached(Boundary boundary) throws IOException; }

    private final AtlasRepository repository;
    private final WorldFolders folders;
    private final Checkpoint checkpoint;

    public WorldCopyRecovery(AtlasRepository repository, WorldFolders folders) {
        this(repository, folders, ignored -> { });
    }

    WorldCopyRecovery(AtlasRepository repository, WorldFolders folders, Checkpoint checkpoint) {
        this.repository = repository;
        this.folders = folders;
        this.checkpoint = checkpoint;
    }

    /** Completes only the known operation; incomplete preparation is never re-copied automatically. */
    public void run(PendingOperation operation) throws IOException, SQLException {
        WorldCopyState state = repository.copyState(operation.id()).orElseThrow(
                () -> new WorldFolders.UncertainCopyException("Legacy copy has no durable preparation evidence"));
        Path target = folders.folder(operation.world());
        Path staging = folders.staging(operation);
        Path backup = folders.backup(operation);
        if (state.phase() == WorldCopyState.Phase.QUEUED) {
            if (exists(staging) || exists(backup)
                    || (operation.kind() == PendingOperation.Kind.CLONE && exists(target)))
                throw new WorldFolders.UncertainCopyException("Unclaimed copy paths already exist");
            repository.advanceCopy(operation.id(), WorldCopyState.Phase.QUEUED, WorldCopyState.Phase.COPYING);
            long originals = 0;
            if (operation.kind() == PendingOperation.Kind.RESET && exists(target)) {
                originals = folders.recordTree(target, entry -> record(operation.id(), "ORIGINAL", entry));
            }
            long prepared = folders.prepareCopy(operation, entry -> record(operation.id(), "PREPARED", entry));
            folders.verifyTree(staging, prepared, path -> entry(operation.id(), "PREPARED", path));
            if (originals > 0) folders.verifyTree(target, originals, path -> entry(operation.id(), "ORIGINAL", path));
            repository.preparedCopy(operation.id(), prepared, originals);
            checkpoint.reached(Boundary.PREPARED);
            state = repository.copyState(operation.id()).orElseThrow();
        } else if (state.phase() == WorldCopyState.Phase.COPYING
                || state.phase() == WorldCopyState.Phase.UNCERTAIN
                || state.phase() == WorldCopyState.Phase.CANCELLED) {
            throw new WorldFolders.UncertainCopyException("Preparation has no complete publication evidence");
        }
        if (state.phase() == WorldCopyState.Phase.PREPARED) {
            verifyPrepared(operation, staging, state);
            if (state.originalEntries() > 0) {
                if (exists(backup)) throw new WorldFolders.UncertainCopyException("Original backup already exists");
                folders.verifyTree(target, state.originalEntries(), path -> entry(operation.id(), "ORIGINAL", path));
                repository.advanceCopy(operation.id(), state.phase(), WorldCopyState.Phase.MOVING_OLD);
            } else {
                if (exists(target) || exists(backup))
                    throw new WorldFolders.UncertainCopyException("Unexpected original tree");
                repository.advanceCopy(operation.id(), state.phase(), WorldCopyState.Phase.PUBLISHING);
            }
            state = repository.copyState(operation.id()).orElseThrow();
        }
        if (state.phase() == WorldCopyState.Phase.MOVING_OLD) {
            verifyPrepared(operation, staging, state);
            if (exists(target) && !exists(backup)) {
                folders.verifyTree(target, state.originalEntries(), path -> entry(operation.id(), "ORIGINAL", path));
                folders.moveTree(target, backup);
                checkpoint.reached(Boundary.OLD_MOVED);
            } else if (!exists(target) && exists(backup)) {
                folders.verifyTree(backup, state.originalEntries(), path -> entry(operation.id(), "ORIGINAL", path));
            } else {
                throw new WorldFolders.UncertainCopyException("Original move has contradictory paths");
            }
            repository.advanceCopy(operation.id(), state.phase(), WorldCopyState.Phase.PUBLISHING);
            state = repository.copyState(operation.id()).orElseThrow();
        }
        if (state.phase() == WorldCopyState.Phase.PUBLISHING) {
            verifyOriginal(operation, backup, state);
            if (exists(staging) && !exists(target)) {
                verifyPrepared(operation, staging, state);
                folders.moveTree(staging, target);
                checkpoint.reached(Boundary.NEW_MOVED);
            } else if (!exists(staging) && exists(target)) {
                verifyPrepared(operation, target, state);
            } else {
                throw new WorldFolders.UncertainCopyException("Publication has contradictory paths");
            }
            verifyPrepared(operation, target, state);
            repository.advanceCopy(operation.id(), state.phase(), WorldCopyState.Phase.PUBLISHED);
            checkpoint.reached(Boundary.PUBLISHED);
            state = repository.copyState(operation.id()).orElseThrow();
        }
        if (state.phase() == WorldCopyState.Phase.PUBLISHED) {
            if (exists(staging)) throw new WorldFolders.UncertainCopyException("Published copy still has a staging tree");
            verifyPrepared(operation, target, state);
            verifyOriginal(operation, backup, state);
            repository.completeCopy(operation);
        } else if (state.phase() != WorldCopyState.Phase.APPLIED) {
            throw new WorldFolders.UncertainCopyException("Unexpected copy phase");
        }
    }

    private void verifyPrepared(PendingOperation operation, Path path, WorldCopyState state) throws IOException {
        folders.verifyTree(path, state.preparedEntries(), name -> entry(operation.id(), "PREPARED", name));
    }

    private void verifyOriginal(PendingOperation operation, Path backup, WorldCopyState state) throws IOException {
        if (state.originalEntries() > 0)
            folders.verifyTree(backup, state.originalEntries(), name -> entry(operation.id(), "ORIGINAL", name));
        else if (exists(backup)) throw new WorldFolders.UncertainCopyException("Unexpected backup tree");
    }

    private void record(long id, String tree, WorldCopyState.Entry entry) throws IOException {
        try { repository.recordCopyEntry(id, tree, entry); }
        catch (SQLException failure) { throw new IOException("Copy evidence could not be stored", failure); }
    }

    private java.util.Optional<WorldCopyState.Entry> entry(long id, String tree, String path) throws IOException {
        try { return repository.copyEntry(id, tree, path); }
        catch (SQLException failure) { throw new IOException("Copy evidence could not be read", failure); }
    }

    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
}
