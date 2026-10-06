package nl.pixelretreat.atlas.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalIndex;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.message.AtlasMessages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/**
 * Portals: storage, the immutable lookup index, fill blocks, and travel to a local world or the
 * other server. Cross-server travel records a ticket in MariaDB before asking the proxy to move
 * the player; the destination consumes it once.
 */
public final class PortalService {
    /** Outcome of a player walking into a portal, mapped to a message by the listener. */
    public enum Travel { ARRIVED, SWITCHING, FAILED, TARGET_MISSING }

    private final Plugin plugin;
    private final AtlasRepository repository;
    private final AtlasWorkers workers;
    private final AtlasConfig config;
    private final TeleportService teleports;
    private final AtlasMessages messages;
    private final AtomicReference<PortalIndex> index = new AtomicReference<>(PortalIndex.EMPTY);
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public PortalService(Plugin plugin, AtlasRepository repository, AtlasWorkers workers, AtlasConfig config,
                         TeleportService teleports, AtlasMessages messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.workers = workers;
        this.config = config;
        this.teleports = teleports;
        this.messages = messages;
    }

    /** The current portal index. */
    public PortalIndex index() { return index.get(); }

    /** Loads every portal and registers the permissions of restricted portals. */
    public CompletableFuture<Void> reload() {
        return workers.submit(repository::loadPortals).thenAccept(portals -> {
            index.set(new PortalIndex(portals));
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                var manager = plugin.getServer().getPluginManager();
                for (Portal portal : portals) {
                    if (portal.restricted() && manager.getPermission(portal.permission()) == null) {
                        manager.addPermission(new Permission(portal.permission(),
                                "Use the restricted Atlas portal " + portal.name(), PermissionDefault.OP));
                    }
                }
            });
        });
    }

    /** Stores a new portal; completes with false when the name is taken. */
    public CompletableFuture<Boolean> create(Portal portal) {
        if (index().named(portal.name()).isPresent()) return CompletableFuture.completedFuture(false);
        return workers.submit(() -> { repository.savePortal(portal); return null; })
                .thenCompose(ignored -> reload()).thenApply(ignored -> true);
    }

    /** Changes a portal; completes with the new value, or empty when it does not exist. */
    public CompletableFuture<Optional<Portal>> update(String name, UnaryOperator<Portal> change) {
        Optional<Portal> old = index().named(name);
        if (old.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        Portal updated = change.apply(old.get());
        return workers.submit(() -> { repository.savePortal(updated); return null; })
                .thenCompose(ignored -> reload())
                .thenApply(ignored -> {
                    if (!old.get().fillBlock().equals(updated.fillBlock())) {
                        old.get().fillBlock().ifPresent(block -> fill(old.get(), Material.AIR, Material.matchMaterial(block)));
                        updated.fillBlock().ifPresent(block -> fill(updated, Material.matchMaterial(block), null));
                    }
                    return Optional.of(updated);
                });
    }

    /** Deletes a portal and removes its fill blocks; completes with whether it existed. */
    public CompletableFuture<Boolean> delete(String name) {
        Optional<Portal> old = index().named(name);
        if (old.isEmpty()) return CompletableFuture.completedFuture(false);
        return workers.submit(() -> repository.deletePortal(name)).thenCompose(deleted -> reload().thenApply(ignored -> {
            if (deleted) old.get().fillBlock().ifPresent(block -> fill(old.get(), Material.AIR, Material.matchMaterial(block)));
            return deleted;
        }));
    }

    /**
     * Sets every block of the portal to {@code block}, one region task per chunk, without block
     * physics. When {@code onlyReplacing} is set, only blocks of that type change (used to clear).
     */
    void fill(Portal portal, Material block, Material onlyReplacing) {
        World world = plugin.getServer().getWorld(NamespacedKey.fromString(portal.world()));
        if (world == null) return;
        BlockData data = block.createBlockData();
        if (data instanceof Orientable orientable && orientable.getAxes().contains(org.bukkit.Axis.X)) {
            boolean alongX = portal.maxX() - portal.minX() >= portal.maxZ() - portal.minZ();
            org.bukkit.Axis axis = alongX ? org.bukkit.Axis.X : org.bukkit.Axis.Z;
            if (orientable.getAxes().contains(axis)) orientable.setAxis(axis);
        }
        for (int chunkX = portal.minX() >> 4; chunkX <= portal.maxX() >> 4; chunkX++) {
            for (int chunkZ = portal.minZ() >> 4; chunkZ <= portal.maxZ() >> 4; chunkZ++) {
                int fromX = Math.max(portal.minX(), chunkX << 4), toX = Math.min(portal.maxX(), (chunkX << 4) + 15);
                int fromZ = Math.max(portal.minZ(), chunkZ << 4), toZ = Math.min(portal.maxZ(), (chunkZ << 4) + 15);
                int cx = chunkX, cz = chunkZ;
                world.getChunkAtAsync(cx, cz).thenRun(() -> plugin.getServer().getRegionScheduler()
                        .run(plugin, world, cx, cz, task -> {
                            for (int x = fromX; x <= toX; x++)
                                for (int y = portal.minY(); y <= portal.maxY(); y++)
                                    for (int z = fromZ; z <= toZ; z++) {
                                        var target = world.getBlockAt(x, y, z);
                                        if (onlyReplacing != null && target.getType() != onlyReplacing) continue;
                                        target.setBlockData(data, false);
                                    }
                        }));
            }
        }
    }

    /**
     * Reserves the player's portal cooldown; returns false while it is still running.
     * Called on the player's thread from the move listener.
     */
    public boolean startCooldown(Player player, Portal portal) {
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) return false;
        cooldowns.put(player.getUniqueId(), now + portal.cooldownMillis());
        return true;
    }

    /** Forgets a player's cooldown when they leave. */
    public void forget(UUID player) { cooldowns.remove(player); }

    /** Sends a player through a portal. Player thread. */
    public CompletableFuture<Travel> travel(Player player, Portal portal) {
        PortalTarget target = portal.target();
        if (target.kind() == PortalTarget.Kind.SERVER) return travelToServer(player, portal);
        World world = plugin.getServer().getWorld(NamespacedKey.fromString(target.world()));
        if (world == null) return CompletableFuture.completedFuture(Travel.TARGET_MISSING);
        return teleports.teleport(player, world, target.point()).thenApply(arrived -> {
            if (arrived) effects(player, portal);
            return arrived ? Travel.ARRIVED : Travel.FAILED;
        });
    }

    private CompletableFuture<Travel> travelToServer(Player player, Portal portal) {
        String region = portal.target().region().orElseThrow();
        String proxyName = config.proxyNames().get(region);
        TravelTicket ticket = new TravelTicket(UUID.randomUUID(), player.getUniqueId(), portal.name(),
                portal.target().world(), portal.target().point());
        Instant expires = Instant.now().plusSeconds(config.travelTicketMinutes() * 60L);
        CompletableFuture<Travel> result = new CompletableFuture<>();
        workers.submit(() -> { repository.issueTicket(ticket, region, expires); return null; }).whenComplete((done, failure) -> {
            if (failure != null) { result.complete(Travel.FAILED); return; }
            boolean scheduled = player.getScheduler().run(plugin, task -> {
                try {
                    effects(player, portal);
                    try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
                        output.writeUTF("Connect");
                        output.writeUTF(proxyName);
                        player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
                    }
                    result.complete(Travel.SWITCHING);
                } catch (IOException | RuntimeException error) {
                    withdraw(ticket);
                    result.complete(Travel.FAILED);
                }
            }, () -> { withdraw(ticket); result.complete(Travel.FAILED); }) != null;
            if (!scheduled) { withdraw(ticket); result.complete(Travel.FAILED); }
        });
        return result;
    }

    private void withdraw(TravelTicket ticket) {
        workers.submit(() -> repository.withdrawTicket(ticket.id())).exceptionally(failure -> {
            messages.warn("diagnostic.travel-ticket-withdraw");
            return false;
        });
    }

    /**
     * Consumes a travel ticket for a player who just joined and teleports them to its target.
     * Completes empty when there was no ticket.
     */
    public CompletableFuture<Optional<Travel>> arrive(Player player) {
        return workers.submit(() -> repository.takeTicket(player.getUniqueId())).thenCompose(ticket -> {
            if (ticket.isEmpty()) return CompletableFuture.completedFuture(Optional.<Travel>empty());
            World world = plugin.getServer().getWorld(NamespacedKey.fromString(ticket.get().world()));
            if (world == null) return CompletableFuture.completedFuture(Optional.of(Travel.TARGET_MISSING));
            return teleports.teleport(player, world, ticket.get().point()).thenApply(arrived -> {
                if (arrived) index().named(ticket.get().portal()).ifPresent(portal -> effects(player, portal));
                return Optional.of(arrived ? Travel.ARRIVED : Travel.FAILED);
            });
        });
    }

    /** Plays the portal's sound and particles to the traveller only. */
    private void effects(Player player, Portal portal) {
        if (portal.sound().isEmpty() && portal.particle().isEmpty()) return;
        player.getScheduler().run(plugin, task -> {
            Location at = player.getLocation();
            portal.sound().ifPresent(sound -> player.playSound(at, sound, 1.0f, 1.0f));
            portal.particle().ifPresent(name -> player.spawnParticle(Particle.valueOf(name), at.add(0, 1, 0), 40, 0.5, 1, 0.5, 0.05));
        }, null);
    }
}
