package nl.pixelretreat.atlas.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import nl.pixelretreat.atlas.world.SpawnPoint;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * One asynchronous teleport into a world: to an exact point, the world's stored spawn, or a safe
 * spot near the vanilla spawn. The safe-spot search runs on the region that owns the spawn chunk.
 */
public final class TeleportService {
    private final Plugin plugin;
    private final RulesService rules;
    private final AtlasConfig config;

    public TeleportService(Plugin plugin, RulesService rules, AtlasConfig config) {
        this.plugin = plugin;
        this.rules = rules;
        this.config = config;
    }

    /** Completes with {@code true} when the player arrived in the world. Callable from any thread. */
    public CompletableFuture<Boolean> teleport(Player player, World world, Optional<SpawnPoint> point) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Optional<SpawnPoint> exact = point.isPresent() ? point : storedSpawn(world);
        if (exact.isPresent()) {
            SpawnPoint spawn = exact.get();
            move(player, new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch()), result);
            return result;
        }
        Location spawn = world.getSpawnLocation();
        int chunkX = spawn.getBlockX() >> 4;
        int chunkZ = spawn.getBlockZ() >> 4;
        world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, failure) -> {
            if (failure != null) { result.complete(false); return; }
            plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> {
                Location safe = safeSpot(world, spawn, config.safeSearchRadius());
                if (safe == null) { result.complete(false); return; }
                move(player, safe, result);
            });
        });
        return result;
    }

    private Optional<SpawnPoint> storedSpawn(World world) {
        RulesSnapshot snapshot = rules.snapshot();
        return snapshot == null ? Optional.empty() : snapshot.worldOrBuiltIn(RulesService.key(world)).spawn();
    }

    private void move(Player player, Location destination, CompletableFuture<Boolean> result) {
        boolean scheduled = player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) { result.complete(false); return; }
            if (player.isInsideVehicle()) player.leaveVehicle();
            if (!player.getPassengers().isEmpty()) player.eject();
            player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((success, error) -> result.complete(error == null && Boolean.TRUE.equals(success)));
        }, () -> result.complete(false)) != null;
        if (!scheduled) result.complete(false);
    }

    /**
     * Searches outward from the spawn for a solid floor with two free blocks above it. Only
     * columns in loaded chunks owned by the current region are inspected.
     */
    static Location safeSpot(World world, Location spawn, int radius) {
        int startX = spawn.getBlockX();
        int startZ = spawn.getBlockZ();
        for (int current = 0; current <= radius; current++) {
            for (int x = startX - current; x <= startX + current; x++) {
                for (int z = startZ - current; z <= startZ + current; z++) {
                    if (Math.abs(x - startX) != current && Math.abs(z - startZ) != current) continue;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)
                            || !org.bukkit.Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) continue;
                    Location found = column(world, x, z, spawn);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static Location column(World world, int x, int z, Location spawn) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int startY = Math.max(minY, Math.min(maxY, spawn.getBlockY()));
        for (int offset = 0; offset <= 48; offset++) {
            for (int y : offset == 0 ? new int[]{startY} : new int[]{startY + offset, startY - offset}) {
                if (y < minY || y > maxY) continue;
                Block feet = world.getBlockAt(x, y, z);
                if (safe(feet)) return new Location(world, x + 0.5, y, z + 0.5, spawn.getYaw(), spawn.getPitch());
            }
        }
        return null;
    }

    private static boolean safe(Block feet) {
        Block head = feet.getRelative(BlockFace.UP);
        Block floor = feet.getRelative(BlockFace.DOWN);
        return feet.isPassable() && head.isPassable() && floor.getType().isSolid()
                && !feet.isLiquid() && !head.isLiquid()
                && !dangerous(feet.getType()) && !dangerous(head.getType()) && !dangerous(floor.getType());
    }

    private static boolean dangerous(Material material) {
        return switch (material) {
            case CACTUS, CAMPFIRE, FIRE, LAVA, MAGMA_BLOCK, POWDER_SNOW, SOUL_CAMPFIRE, SOUL_FIRE,
                 SWEET_BERRY_BUSH, WITHER_ROSE -> true;
            default -> false;
        };
    }
}
