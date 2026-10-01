package nl.pixelretreat.atlas.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import nl.pixelretreat.atlas.keeploaded.KeepLoadedRegion;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Keeps chunk rectangles loaded with plugin chunk tickets. The region list is an immutable snapshot;
 * tickets are only added or removed on the global region scheduler.
 */
public final class KeepLoadedService {
    private final Plugin plugin;
    private final AtlasRepository repository;
    private final AtlasWorkers workers;
    private final AtomicReference<List<KeepLoadedRegion>> regions = new AtomicReference<>(List.of());
    /** World key → chunk keys holding an Atlas ticket. Global region thread only. */
    private final Map<String, Set<Long>> ticketed = new HashMap<>();

    public KeepLoadedService(Plugin plugin, AtlasRepository repository, AtlasWorkers workers) {
        this.plugin = plugin;
        this.repository = repository;
        this.workers = workers;
    }

    /** Loads the regions and reconciles every world's tickets. */
    public CompletableFuture<Void> reload() {
        return workers.submit(repository::loadRegions).thenAccept(loaded -> {
            regions.set(List.copyOf(loaded));
            reconcile();
        });
    }

    /** Saves a region and applies it. */
    public CompletableFuture<Void> save(KeepLoadedRegion region) {
        return workers.submit(() -> { repository.saveRegion(region); return null; }).thenCompose(ignored -> reload());
    }

    /** Deletes a region; completes with whether it existed. */
    public CompletableFuture<Boolean> delete(String name) {
        return workers.submit(() -> repository.deleteRegion(name))
                .thenCompose(deleted -> reload().thenApply(ignored -> deleted));
    }

    /** All regions sorted by name. */
    public List<KeepLoadedRegion> regions() {
        return regions.get().stream().sorted(Comparator.comparing(KeepLoadedRegion::name)).toList();
    }

    /** Whether a region keeps chunks of this world loaded. */
    public boolean anyIn(String worldKey) {
        return regions.get().stream().anyMatch(region -> region.world().equals(worldKey));
    }

    /** Number of chunk tickets Atlas currently holds. Global region thread for an exact value. */
    public int ticketCount() {
        return ticketed.values().stream().mapToInt(Set::size).sum();
    }

    private void reconcile() {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            Map<String, Set<Long>> desired = desired(regions.get());
            Set<String> worldKeys = new HashSet<>(desired.keySet());
            worldKeys.addAll(ticketed.keySet());
            for (String worldKey : worldKeys) {
                World world = plugin.getServer().getWorld(NamespacedKey.fromString(worldKey));
                if (world == null) { ticketed.remove(worldKey); continue; }
                Set<Long> want = desired.getOrDefault(worldKey, Set.of());
                Set<Long> have = ticketed.computeIfAbsent(worldKey, ignored -> new HashSet<>());
                for (Long chunk : List.copyOf(have)) {
                    if (want.contains(chunk)) continue;
                    world.removePluginChunkTicket(chunkX(chunk), chunkZ(chunk), plugin);
                    have.remove(chunk);
                }
                for (Long chunk : want) {
                    if (have.contains(chunk)) continue;
                    if (world.addPluginChunkTicket(chunkX(chunk), chunkZ(chunk), plugin)) have.add(chunk);
                }
                if (have.isEmpty()) ticketed.remove(worldKey);
            }
        });
    }

    /** Releases every ticket; called from onDisable. */
    public void releaseAll() {
        for (World world : plugin.getServer().getWorlds()) world.removePluginChunkTickets(plugin);
        ticketed.clear();
    }

    static Map<String, Set<Long>> desired(Collection<KeepLoadedRegion> regions) {
        Map<String, Set<Long>> desired = new HashMap<>();
        for (KeepLoadedRegion region : regions) {
            Set<Long> chunks = desired.computeIfAbsent(region.world(), ignored -> new HashSet<>());
            for (int x = region.minChunkX(); x <= region.maxChunkX(); x++) {
                for (int z = region.minChunkZ(); z <= region.maxChunkZ(); z++) chunks.add(chunkKey(x, z));
            }
        }
        return desired;
    }

    static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    static int chunkX(long key) { return (int) (key >> 32); }
    static int chunkZ(long key) { return (int) key; }
}
