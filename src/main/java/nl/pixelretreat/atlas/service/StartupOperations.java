package nl.pixelretreat.atlas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.WorldFolders;
import nl.pixelretreat.atlas.world.WorldRecord;

/**
 * Runs queued folder work during plugin loading, before Minecraft loads any world. Each operation
 * is checked again against the current database state; a failure is recorded and never retried
 * automatically.
 */
public final class StartupOperations {
    /** The outcome of one operation, reported once in the console by the caller. */
    public record Outcome(PendingOperation operation, boolean success, String detail) { }

    private final AtlasRepository repository;
    private final WorldFolders folders;

    public StartupOperations(AtlasRepository repository, WorldFolders folders) {
        this.repository = repository;
        this.folders = folders;
    }

    /** Executes every pending operation of this server in queue order. */
    public List<Outcome> run() throws java.sql.SQLException {
        List<Outcome> outcomes = new ArrayList<>();
        for (PendingOperation operation : repository.pending()) {
            Outcome outcome;
            try {
                outcome = execute(operation, repository.loadWorlds());
            } catch (Exception failure) {
                outcome = new Outcome(operation, false, failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            }
            repository.finish(operation.id(), outcome.success(), outcome.detail());
            outcomes.add(outcome);
        }
        return outcomes;
    }

    private Outcome execute(PendingOperation operation, Map<String, WorldRecord> worlds) throws Exception {
        WorldRecord target = worlds.get(operation.world());
        return switch (operation.kind()) {
            case DELETE -> {
                if (target != null && target.enabled()) yield new Outcome(operation, false, "world is enabled again");
                folders.delete(operation.world());
                repository.deleteWorld(operation.world());
                yield new Outcome(operation, true, "deleted");
            }
            case CLONE -> {
                if (target == null) yield new Outcome(operation, false, "target world no longer exists");
                folders.copy(operation.source().orElseThrow(), operation.world());
                yield new Outcome(operation, true, "cloned");
            }
            case RESET -> {
                if (target == null || target.builtIn()) yield new Outcome(operation, false, "target is not an Atlas world");
                folders.replace(operation.source().orElseThrow(), operation.world());
                yield new Outcome(operation, true, "reset");
            }
        };
    }
}
