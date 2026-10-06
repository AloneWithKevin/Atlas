package nl.pixelretreat.atlas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.WorldFolders;
import nl.pixelretreat.atlas.world.WorldRecord;
import nl.pixelretreat.atlas.world.WorldCopyState;

/**
 * Runs queued folder work during plugin loading, before Minecraft loads any world. Each operation
 * is checked against database state and startup declarations. Only sealed, verified copy
 * publication may resume; unsealed or contradictory evidence is preserved for an owner decision.
 */
public final class StartupOperations {
    /** The outcome of one operation, reported once in the console by the caller. */
    public record Outcome(PendingOperation operation, boolean success, String detail) { }

    private final AtlasRepository repository;
    private final WorldFolders folders;
    private final Set<String> declaredAtStartup;

    public StartupOperations(AtlasRepository repository, WorldFolders folders) {
        this(repository, folders, Set.of());
    }

    public StartupOperations(AtlasRepository repository, WorldFolders folders, Set<String> declaredAtStartup) {
        this.repository = repository;
        this.folders = folders;
        this.declaredAtStartup = Set.copyOf(declaredAtStartup);
    }

    /** Executes every pending operation of this server in queue order. */
    public List<Outcome> run() throws java.sql.SQLException {
        List<Outcome> outcomes = new ArrayList<>();
        for (PendingOperation operation : repository.pending()) {
            Outcome outcome;
            if (operation.kind() != PendingOperation.Kind.DELETE && repository.copyState(operation.id()).isEmpty()) {
                repository.rejectLegacyCopy(operation);
                outcomes.add(new Outcome(operation, false, WorldCopyState.Phase.UNCERTAIN.name()));
                continue;
            }
            // The pack was already read before onLoad. Never change folders for a declared dimension.
            if (declaredAtStartup.contains(operation.world())) {
                outcomes.add(new Outcome(operation, false, "PENDING"));
                continue;
            }
            try {
                outcome = execute(operation, repository.loadWorlds());
            } catch (IOException failure) {
                if (operation.kind() != PendingOperation.Kind.DELETE) {
                    WorldCopyState state = repository.copyState(operation.id()).orElseThrow();
                    if (failure instanceof WorldFolders.UncertainCopyException
                            || state.phase() == WorldCopyState.Phase.COPYING
                            || state.phase() == WorldCopyState.Phase.QUEUED) {
                        repository.uncertainCopy(operation, failure.getClass().getSimpleName());
                        outcome = new Outcome(operation, false, WorldCopyState.Phase.UNCERTAIN.name());
                    } else {
                        // Known prepared evidence may be checked again; no source re-copy or guessed rollback.
                        outcome = new Outcome(operation, false, state.phase().name());
                    }
                    outcomes.add(outcome);
                    continue;
                }
                outcome = new Outcome(operation, false, failure.getClass().getSimpleName());
            }
            if (operation.kind() == PendingOperation.Kind.DELETE)
                repository.finish(operation.id(), outcome.success(), outcome.detail());
            outcomes.add(outcome);
        }
        return outcomes;
    }

    private Outcome execute(PendingOperation operation, Map<String, WorldRecord> worlds)
            throws IOException, java.sql.SQLException {
        WorldRecord target = worlds.get(operation.world());
        return switch (operation.kind()) {
            case DELETE -> {
                if (target != null && target.enabled()) yield new Outcome(operation, false, "operation.delete-reenabled");
                folders.delete(operation.world());
                repository.deleteWorld(operation.world());
                yield new Outcome(operation, true, "deleted");
            }
            case CLONE -> {
                if (target == null || target.builtIn() || target.enabled())
                    throw new WorldFolders.UncertainCopyException("Copy target metadata conflicts");
                new WorldCopyRecovery(repository, folders).run(operation);
                yield new Outcome(operation, true, "cloned");
            }
            case RESET -> {
                if (target == null || target.builtIn() || target.enabled())
                    throw new WorldFolders.UncertainCopyException("Reset target metadata conflicts");
                new WorldCopyRecovery(repository, folders).run(operation);
                yield new Outcome(operation, true, "reset");
            }
        };
    }
}
