package nl.pixelretreat.atlas.service;

import dev.veyra.api.WorldLifecycle;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import nl.pixelretreat.atlas.api.AtlasWorldAccess;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.SpawnPoint;
import nl.pixelretreat.atlas.world.WorldNames;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/** Publishes persistent access before draining original in-flight arrivals and evacuating players. */
public final class WorldAccessService implements AtlasWorldAccess, Listener {
    public static final String BYPASS = "atlas.world.closed.bypass";
    private final Plugin plugin;
    private final AtlasRepository repository;
    private final RulesService rules;
    private final TeleportService teleports;
    private final WorldLifecycle nativeWorlds;
    private final AtlasMessages messages;
    private final Set<String> changing = ConcurrentHashMap.newKeySet();

    /** The same per-key gate also owns native enable/disable through their final source publication. */
    boolean beginLifecycle(String key) { return changing.add(key); }
    void endLifecycle(String key) { changing.remove(key); }

    public WorldAccessService(Plugin plugin, AtlasRepository repository, RulesService rules,
                              TeleportService teleports, WorldLifecycle nativeWorlds, AtlasMessages messages) {
        this.plugin = plugin; this.repository = repository; this.rules = rules;
        this.teleports = teleports; this.nativeWorlds = nativeWorlds; this.messages = messages;
    }

    @Override public boolean closed(String key) {
        var snapshot = rules.snapshot();
        return snapshot != null && snapshot.worldOrBuiltIn(key).closed();
    }

    @Override public CompletableFuture<Result> setClosed(String key, boolean close) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        if (parsed == null || rules.snapshot() == null
                || (!WorldNames.builtIn(key) && rules.snapshot().world(key).isEmpty()))
            return CompletableFuture.completedFuture(Result.UNKNOWN_WORLD);
        if (!beginLifecycle(key)) return CompletableFuture.completedFuture(Result.BUSY);
        CompletableFuture<Result> result;
        try {
            // Rendering can fail independently of the source; do it before committing access.
            var refusal = close ? messages.render("world.closed-entry", Map.of("world", WorldNames.shortName(key))) : null;
            result = (close ? nativeWorlds.spawn(plugin).toCompletableFuture()
                    : CompletableFuture.completedFuture(Optional.<WorldLifecycle.Destination>empty()))
                .thenCompose(spawn -> global(() -> {
                    if (!close) return true;
                    if (spawn == null || spawn.isEmpty()) return false;
                    World destination = plugin.getServer().getWorld(spawn.get().world());
                    return destination != null && !RulesService.key(destination).equals(key)
                            && !closed(RulesService.key(destination));
                }).thenCompose(valid -> {
                    if (!valid) return CompletableFuture.completedFuture(Result.SPAWN_UNAVAILABLE);
                    return rules.serialized(() -> {
                        repository.setClosed(key, close);
                        rules.publishAccess(key, close);
                        // Publication and the native move barrier share the ordered source update.
                        return nativeWorlds.closed(plugin, parsed, close, refusal, spawn.orElse(null));
                    }).thenCompose(barrier -> {
                        if (!close) return CompletableFuture.completedFuture(Result.DONE);
                        var destination = spawn.orElseThrow();
                        return sweep(key, destination).thenCompose(first -> bounded(barrier)
                            .handle((ignored, failure) -> failure == null)
                            .thenCompose(settled -> sweep(key, destination)
                                .thenApply(second -> settled && first && second ? Result.DONE : Result.EVICTION_INCOMPLETE)));
                    });
                })).exceptionally(failure -> close && closed(key) ? Result.EVICTION_INCOMPLETE : Result.FAILED);
        } catch (RuntimeException failure) { result = CompletableFuture.completedFuture(Result.FAILED); }
        // A consumer may cancel/time out its response; original custody and cleanup must continue.
        return result.whenComplete((done, failure) -> endLifecycle(key)).copy();
    }

    /** Reinstates persisted restrictions after catalog registration, including worlds loaded later. */
    public void publishStored() {
        for (var record : rules.snapshot().worlds()) {
            nativeWorlds.closed(plugin, NamespacedKey.fromString(record.key()), record.closed(),
                    messages.render("world.closed-entry", Map.of("world", WorldNames.shortName(record.key()))), null);
        }
    }

    private CompletableFuture<Boolean> sweep(String key, WorldLifecycle.Destination destination) {
        return global(() -> java.util.List.copyOf(plugin.getServer().getOnlinePlayers()))
            .thenCompose(players -> {
                var futures = players.stream().map(player -> evacuate(player, key, destination)).toList();
                return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> futures.stream().allMatch(future -> Boolean.TRUE.equals(future.getNow(false))));
            });
    }

    private CompletableFuture<Boolean> evacuate(Player player, String key, WorldLifecycle.Destination destination) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (player.getScheduler().run(plugin, task -> {
            if (!player.isOnline() || !closed(key) || bypass(player) || !RulesService.key(player.getWorld()).equals(key)) {
                result.complete(true); return;
            }
            World target = plugin.getServer().getWorld(destination.world());
            if (target == null || closed(RulesService.key(target))) { result.complete(false); return; }
            var point = new SpawnPoint(destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
            teleports.teleportDetailed(player, target, Optional.of(point), () -> closed(key)
                    && !bypass(player) && RulesService.key(player.getWorld()).equals(key)
                    && !closed(RulesService.key(target)))
                .whenComplete((outcome, failure) -> result.complete(failure == null && outcome == TeleportService.Outcome.ARRIVED));
        }, () -> result.complete(true)) == null) result.complete(true);
        return bounded(result).exceptionally(failure -> false);
    }

    private static boolean bypass(Player player) { return player.isOp() || player.hasPermission(BYPASS); }

    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent event) { guard(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void changed(PlayerChangedWorldEvent event) { guard(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void respawned(PlayerRespawnEvent event) { guard(event.getPlayer()); }

    private void guard(Player player) {
        // The original transition must finish before an independent evacuation starts.
        player.getScheduler().runDelayed(plugin, task -> {
            String key = RulesService.key(player.getWorld());
            if (!closed(key) || bypass(player)) return;
            nativeWorlds.spawn(plugin).whenComplete((spawn, failure) -> {
                if (failure == null && spawn != null && spawn.isPresent()) evacuate(player, key, spawn.get());
            });
        }, null, 1L);
    }

    private <T> CompletableFuture<T> global(java.util.concurrent.Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try { plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    private static <T> CompletableFuture<T> bounded(java.util.concurrent.CompletionStage<T> original) {
        // Timeout belongs to this waiter; never cancel or complete native custody evidence.
        return original.thenApply(value -> value).toCompletableFuture().orTimeout(60, TimeUnit.SECONDS);
    }
}
