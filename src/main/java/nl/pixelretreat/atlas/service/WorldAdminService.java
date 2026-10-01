package nl.pixelretreat.atlas.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.DatapackWriter;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import nl.pixelretreat.atlas.world.WorldFolders;
import nl.pixelretreat.atlas.world.WorldGenerator;
import nl.pixelretreat.atlas.world.WorldNames;
import nl.pixelretreat.atlas.world.WorldRecord;

/**
 * World lifecycle. Changes are written to MariaDB and the Atlas data pack immediately; Minecraft
 * loads the new world set at the next start, when queued folder work also runs.
 */
public final class WorldAdminService {
    /** Result of a lifecycle request, mapped one to one to a message key by the command. */
    public enum Result {
        DONE, UNKNOWN_WORLD, BUILT_IN, ALREADY_EXISTS, FOLDER_EXISTS, FOLDER_MISSING, HAS_PORTALS,
        HAS_REGIONS, NO_SOURCE, SAME_WORLD, ALREADY_ENABLED, ALREADY_DISABLED, NOT_FOUND
    }

    private final AtlasRepository repository;
    private final AtlasWorkers workers;
    private final RulesService rules;
    private final DatapackWriter datapack;
    private final WorldFolders folders;
    private final PortalService portals;
    private final KeepLoadedService keepLoaded;

    public WorldAdminService(AtlasRepository repository, AtlasWorkers workers, RulesService rules,
                             DatapackWriter datapack, WorldFolders folders, PortalService portals,
                             KeepLoadedService keepLoaded) {
        this.repository = repository;
        this.workers = workers;
        this.rules = rules;
        this.datapack = datapack;
        this.folders = folders;
        this.portals = portals;
        this.keepLoaded = keepLoaded;
    }

    /** Declares a new world with fresh terrain. */
    public CompletableFuture<Result> create(String key, WorldGenerator generator) {
        return change(() -> {
            if (exists(key)) return Result.ALREADY_EXISTS;
            if (folders.exists(key)) return Result.FOLDER_EXISTS;
            return repository.insertWorld(key, generator, Optional.empty()) ? Result.DONE : Result.ALREADY_EXISTS;
        });
    }

    /** Declares a world whose folder was placed in {@code dimensions/atlas/<name>} by staff. */
    public CompletableFuture<Result> importWorld(String key, WorldGenerator generator) {
        return change(() -> {
            if (exists(key)) return Result.ALREADY_EXISTS;
            if (!folders.exists(key)) return Result.FOLDER_MISSING;
            return repository.insertWorld(key, generator, Optional.empty()) ? Result.DONE : Result.ALREADY_EXISTS;
        });
    }

    /** Declares or undeclares a world from the next start on. */
    public CompletableFuture<Result> setEnabled(String key, boolean enabled) {
        return change(() -> {
            if (WorldNames.builtIn(key)) return Result.BUILT_IN;
            Optional<WorldRecord> world = current().world(key);
            if (world.isEmpty() || world.get().generator().isEmpty()) return Result.UNKNOWN_WORLD;
            if (world.get().enabled() == enabled) return enabled ? Result.ALREADY_ENABLED : Result.ALREADY_DISABLED;
            if (!enabled) {
                if (portals.index().anyIn(key)) return Result.HAS_PORTALS;
                if (keepLoaded.anyIn(key)) return Result.HAS_REGIONS;
            }
            repository.setEnabled(key, enabled);
            return Result.DONE;
        });
    }

    /** Undeclares a world now and deletes its folder at the next start. */
    public CompletableFuture<Result> delete(String key, UUID actor) {
        return change(() -> {
            if (WorldNames.builtIn(key)) return Result.BUILT_IN;
            Optional<WorldRecord> world = current().world(key);
            if (world.isEmpty() || world.get().generator().isEmpty()) return Result.UNKNOWN_WORLD;
            if (portals.index().anyIn(key)) return Result.HAS_PORTALS;
            if (keepLoaded.anyIn(key)) return Result.HAS_REGIONS;
            repository.setEnabled(key, false);
            repository.queue(PendingOperation.Kind.DELETE, key, Optional.empty(), actor);
            return Result.DONE;
        });
    }

    /** Declares a copy of a world; the folder is copied at the next start. */
    public CompletableFuture<Result> cloneWorld(String sourceKey, String targetKey, UUID actor) {
        return change(() -> {
            if (sourceKey.equals(targetKey)) return Result.SAME_WORLD;
            if (WorldNames.builtIn(targetKey)) return Result.BUILT_IN;
            if (!knownSource(sourceKey)) return Result.UNKNOWN_WORLD;
            if (exists(targetKey)) return Result.ALREADY_EXISTS;
            if (folders.exists(targetKey)) return Result.FOLDER_EXISTS;
            WorldGenerator generator = current().world(sourceKey).flatMap(WorldRecord::generator)
                    .orElse(WorldGenerator.forBuiltIn(sourceKey));
            if (!repository.insertWorld(targetKey, generator, Optional.of(sourceKey))) return Result.ALREADY_EXISTS;
            repository.queue(PendingOperation.Kind.CLONE, targetKey, Optional.of(sourceKey), actor);
            return Result.DONE;
        });
    }

    /** Replaces a world with a copy of its source at the next start. */
    public CompletableFuture<Result> reset(String key, Optional<String> source, UUID actor) {
        return change(() -> {
            if (WorldNames.builtIn(key)) return Result.BUILT_IN;
            Optional<WorldRecord> world = current().world(key);
            if (world.isEmpty() || world.get().generator().isEmpty()) return Result.UNKNOWN_WORLD;
            Optional<String> from = source.isPresent() ? source : world.get().resetSource();
            if (from.isEmpty()) return Result.NO_SOURCE;
            if (from.get().equals(key)) return Result.SAME_WORLD;
            if (!knownSource(from.get())) return Result.UNKNOWN_WORLD;
            if (keepLoaded.anyIn(key)) return Result.HAS_REGIONS;
            repository.queue(PendingOperation.Kind.RESET, key, from, actor);
            return Result.DONE;
        });
    }

    /** Queued operations of this server. */
    public CompletableFuture<List<PendingOperation>> pending() {
        return workers.submit(repository::pending);
    }

    /** Cancels a queued operation and undoes what it already declared. */
    public CompletableFuture<Result> cancel(long id) {
        return change(() -> {
            Optional<PendingOperation> cancelled = repository.cancel(id);
            if (cancelled.isEmpty()) return Result.NOT_FOUND;
            switch (cancelled.get().kind()) {
                case CLONE -> repository.deleteWorld(cancelled.get().world());
                case DELETE -> repository.setEnabled(cancelled.get().world(), true);
                case RESET -> { }
            }
            return Result.DONE;
        });
    }

    private boolean exists(String key) {
        return WorldNames.builtIn(key) || current().world(key).flatMap(WorldRecord::generator).isPresent();
    }

    private boolean knownSource(String key) throws java.io.IOException {
        return folders.exists(key) && (WorldNames.builtIn(key) || current().world(key).isPresent());
    }

    private RulesSnapshot current() {
        RulesSnapshot snapshot = rules.snapshot();
        if (snapshot == null) throw new IllegalStateException("Atlas rules are not loaded");
        return snapshot;
    }

    /** Runs a change on a worker, then rewrites the data pack and publishes fresh rules. */
    private CompletableFuture<Result> change(java.util.concurrent.Callable<Result> action) {
        return workers.submit(() -> {
            Result result = action.call();
            if (result == Result.DONE) {
                var worlds = repository.loadWorlds();
                datapack.synchronize(worlds.values());
                rules.publish(worlds);
            }
            return result;
        });
    }
}
