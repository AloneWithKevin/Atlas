package nl.pixelretreat.atlas.service;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalIndex;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.controller.api.*;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.statistics.AtlasStatistics;
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
    public enum Travel { ARRIVED, SWITCHING, FAILED, TARGET_MISSING, INVALID_TARGET }

    private final Plugin plugin;
    private final AtlasRepository repository;
    private final AtlasWorkers workers;
    private final AtlasConfig config;
    private final TeleportService teleports;
    private final AtlasMessages messages;
    private final AtomicReference<PortalIndex> index = new AtomicReference<>(PortalIndex.EMPTY);
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final AtomicBoolean invalidTargetWarned = new AtomicBoolean();
    private final Object mutationLock = new Object();
    private volatile AtlasStatistics statistics;
    private InventoryTransferApi transfers;
    private BoundConnectionsApi bindings;
    private dev.veyra.api.PlayerCheckpoints checkpoints;
    private volatile boolean closed;
    private record Preparing(Player original,CompletableFuture<InventoryTransferApi.Preparation> result) { }
    private final Map<UUID,Preparing> preparing=new ConcurrentHashMap<>();
    public void transfers(InventoryTransferApi transfers,BoundConnectionsApi bindings,dev.veyra.api.PlayerCheckpoints checkpoints){this.transfers=java.util.Objects.requireNonNull(transfers);this.bindings=java.util.Objects.requireNonNull(bindings);this.checkpoints=java.util.Objects.requireNonNull(checkpoints);}
    public void close(){closed=true;preparing.values().forEach(p->p.result().complete(InventoryTransferApi.Preparation.UNKNOWN));preparing.clear();}
    public void transferFinished(TransferPlan plan){if(plan.owner().equals("atlas"))workers.submit(()->{repository.transferTerminal(plan);return null;});}


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
    public void statistics(AtlasStatistics statistics) { this.statistics = statistics; }

    /** Best-effort measurement of the real portal trigger/refusal on the player's entity thread. */
    public void recordAttempt(UUID player, String portal, String outcome, String reason) {
        AtlasStatistics current = statistics;
        if (current != null) current.observePortalAttempt(player, portal, outcome, reason);
    }

    public void onPlayerScheduler(Player player, java.util.function.Consumer<Player> action) {
        player.getScheduler().run(plugin, task -> action.accept(player), null);
    }

    /** Loads every portal and registers the permissions of restricted portals. */
    public CompletableFuture<Void> reload() {
        return workers.submit(() -> {
            synchronized (mutationLock) { reloadNow(); }
            return null;
        });
    }

    private void reloadNow() throws java.sql.SQLException {
        List<Portal> portals = repository.loadPortals();
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
    }

    /** Stores a new portal; completes with false when the name is taken. */
    public CompletableFuture<Boolean> create(Portal portal) {
        return workers.submit(() -> {
            synchronized (mutationLock) {
                boolean created = repository.insertPortal(portal);
                reloadNow();
                return created;
            }
        });
    }

    /** Changes a portal; completes with the new value, or empty when it does not exist. */
    public CompletableFuture<Optional<Portal>> update(String name, UnaryOperator<Portal> change) {
        return workers.submit(() -> {
            synchronized (mutationLock) {
                Optional<Portal> old = index().named(name);
                if (old.isEmpty()) return Optional.<Portal>empty();
                Portal updated = change.apply(old.get());
                repository.savePortal(updated);
                reloadNow();
                if (!old.get().fillBlock().equals(updated.fillBlock())) {
                    old.get().fillBlock().ifPresent(block -> fill(old.get(), Material.AIR, Material.matchMaterial(block)));
                    updated.fillBlock().ifPresent(block -> fill(updated, Material.matchMaterial(block), null));
                }
                return Optional.of(updated);
            }
        });
    }

    /** Deletes a portal and removes its fill blocks; completes with whether it existed. */
    public CompletableFuture<Boolean> delete(String name) {
        return workers.submit(() -> {
            synchronized (mutationLock) {
                Optional<Portal> old = index().named(name);
                if (old.isEmpty()) return false;
                boolean deleted = repository.deletePortal(name);
                reloadNow();
                if (deleted) old.get().fillBlock().ifPresent(block -> fill(old.get(), Material.AIR, Material.matchMaterial(block)));
                return deleted;
            }
        });
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
                            for (long x = fromX; x <= toX; x++)
                                for (long y = Math.max(portal.minY(), world.getMinHeight());
                                     y <= Math.min((long) portal.maxY(), (long) world.getMaxHeight() - 1); y++)
                                    for (long z = fromZ; z <= toZ; z++) {
                                        var target = world.getBlockAt((int) x, (int) y, (int) z);
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
    public void forget(Player original) {cooldowns.remove(original.getUniqueId());preparing.values().forEach(p->{if(p.original()==original)p.result().complete(InventoryTransferApi.Preparation.UNKNOWN);});}

    /** Sends a player through a portal. Player thread. */
    public CompletableFuture<Travel> travel(Player player, Portal portal) {
        PortalTarget target = portal.target();
        if (target.kind() == PortalTarget.Kind.SERVER) return travelToServer(player, portal);
        String sourceWorld = RulesService.key(player.getWorld());
        UUID playerId = player.getUniqueId();
        World world = plugin.getServer().getWorld(NamespacedKey.fromString(target.world()));
        UUID operation = UUID.randomUUID();
        CompletableFuture<Travel> result = new CompletableFuture<>();
        var admission = statistics != null && !statistics.capturesPortalTrips()
                ? CompletableFuture.<Void>completedFuture(null)
                : workers.submit(() -> { repository.startLocalPortalTrip(operation, playerId, portal.name(),
                    sourceWorld, target.world()); return (Void) null; });
        admission.whenComplete((started, startFailure) -> {
            if (startFailure != null) {
                recordAttempt(playerId, portal.name(), "refused", "statistics_admission");
                result.complete(Travel.FAILED);
                return;
            }
            recordAttempt(playerId, portal.name(), "accepted", "none");
            if (world == null) {
                finish(operation, "target_missing", Travel.TARGET_MISSING, result);
                return;
            }
            boolean scheduled = player.getScheduler().run(plugin, task -> {
                if (!player.isOnline() || !authorized(player, portal)) { finish(operation, "not_performed", Travel.FAILED, result); return; }
                teleports.teleportDetailed(player, world, target.point(), () -> authorized(player, portal)).whenComplete((outcome, failure) -> {
                    if (failure != null || outcome == TeleportService.Outcome.UNKNOWN) {
                        unresolved(operation, "teleport_result_unknown");
                        result.complete(Travel.FAILED);
                    } else if (outcome == TeleportService.Outcome.ARRIVED) {
                        effects(player, portal);
                        finish(operation, "arrived", Travel.ARRIVED, result);
                    } else finish(operation, "not_performed", Travel.FAILED, result);
                });
            }, () -> finish(operation, "not_performed", Travel.FAILED, result)) != null;
            if (!scheduled) finish(operation, "not_performed", Travel.FAILED, result);
        });
        return result;
    }

    private CompletableFuture<Travel> travelToServer(Player player, Portal portal) {
        String region = portal.target().region().orElseThrow();
        if (!config.routesTo(region)) {
            if (invalidTargetWarned.compareAndSet(false, true)) messages.warn("portal.invalid-region");
            return CompletableFuture.completedFuture(Travel.INVALID_TARGET);
        }
        String proxyName = config.proxyNames().get(region);
        String sourceWorld = RulesService.key(player.getWorld());
        TravelTicket ticket = new TravelTicket(UUID.randomUUID(), player.getUniqueId(), portal.name(),
                portal.target().world(), portal.target().point());
        Instant expires = Instant.now().plusSeconds(config.travelTicketMinutes() * 60L);
        CompletableFuture<Travel> result = new CompletableFuture<>();
        if(transfers==null||bindings==null||closed||!transfers.routingReady())return CompletableFuture.completedFuture(Travel.FAILED);
        bindings.capture(player).thenCompose(bound->{
            var proof=bound.orElseThrow(()->new IllegalStateException("Original portal source unavailable")).connection();
            var source=proof.backend().orElseThrow();
            if(!source.serverName().equals(config.proxyNames().get(config.serverId())))throw new IllegalStateException("Portal source mapping changed");
            var plan=new TransferPlan(ticket.id(),"atlas",ticket.player(),proof.session().orElseThrow(),config.serverId(),source.serverName(),source.visitId(),region,proxyName);
            return workers.submit(()->{repository.issueTransferTicket(ticket,plan,expires,sourceWorld);return plan;});
        }).whenComplete((plan,failure)->{
            if(failure!=null){result.complete(Travel.FAILED);return;}
            boolean scheduled=player.getScheduler().run(plugin,task->{
                if(closed||!plugin.isEnabled()||!player.isOnline()||plugin.getServer().getPlayer(ticket.player())!=player||!authorized(player,portal)) {
                    withdraw(ticket,true);result.complete(Travel.FAILED);return;
                }
                try {
                    effects(player,portal);
                    transfers.transfer(plugin,plan,player).whenComplete((receipt,transferFailure)->{
                        if(transferFailure!=null||receipt==null){withdraw(ticket,false);result.complete(Travel.FAILED);return;}
                        if(receipt.phase()==InventoryTransferApi.Phase.REFUSED){withdraw(ticket,true);result.complete(Travel.FAILED);return;}
                        recordAttempt(ticket.player(),portal.name(),"accepted","none");result.complete(Travel.SWITCHING);
                    });
                }catch(RuntimeException unknown){withdraw(ticket,false);result.complete(Travel.FAILED);}
            },()->{withdraw(ticket,true);result.complete(Travel.FAILED);})!=null;
            if(!scheduled){withdraw(ticket,true);result.complete(Travel.FAILED);}
        });
        return result;
    }

    /** Prepares the original portal destination while native first chunks are still gated. */
    public java.util.concurrent.CompletionStage<InventoryTransferApi.Preparation> prepareArrival(TransferPlan plan,Player original) {
        if(closed||bindings==null||!plan.owner().equals("atlas"))return CompletableFuture.completedStage(InventoryTransferApi.Preparation.UNKNOWN);
        var known=preparing.get(plan.id());if(known!=null)return known.original()==original?known.result():CompletableFuture.completedStage(InventoryTransferApi.Preparation.UNKNOWN);
        if(preparing.size()>=128)return CompletableFuture.completedStage(InventoryTransferApi.Preparation.WAITING);
        var pending=new Preparing(original,new CompletableFuture<>());known=preparing.putIfAbsent(plan.id(),pending);
        if(known!=null)return known.original()==original?known.result():CompletableFuture.completedStage(InventoryTransferApi.Preparation.UNKNOWN);
        pending.result().whenComplete((v,f)->preparing.remove(plan.id(),pending));
        bindings.capture(original).thenCompose(bound->{
            if(bound.isEmpty()||!bound.get().connection().session().filter(plan.session()::equals).isPresent()
                ||!bound.get().connection().backend().filter(b->b.serverName().equals(plan.targetBackend())).isPresent())
                return CompletableFuture.<Optional<AtlasRepository.TransferArrival>>completedStage(Optional.empty());
            return workers.submit(()->repository.claimTransferArrival(plan));
        }).whenComplete((arrival,failure)->{
            if(failure!=null||arrival.isEmpty()){pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);return;}
            var claimed=arrival.get();
            if(java.util.Set.of("ARRIVED","NOT_MOVED").contains(claimed.phase())){pending.result().complete(InventoryTransferApi.Preparation.READY);return;}
            if(!claimed.claimed()){pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);return;}
            if(original.getScheduler().run(plugin,task->{
                if(closed||!plugin.isEnabled()||!original.isOnline()||plugin.getServer().getPlayer(plan.player())!=original){pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);return;}
                var ticket=claimed.ticket();var world=plugin.getServer().getWorld(NamespacedKey.fromString(ticket.world()));
                if(world==null||!(original.isOp()||original.hasPermission("atlas.portal.use"))){preparedOutcome(plan,pending,false);return;}
                teleports.teleportDetailed(original,world,ticket.point(),()->!closed&&original.isOnline()&&plugin.getServer().getPlayer(plan.player())==original
                    &&(original.isOp()||original.hasPermission("atlas.portal.use"))).whenComplete((outcome,teleportFailure)->{
                    if(teleportFailure!=null||outcome==null||outcome==TeleportService.Outcome.UNKNOWN){pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);return;}
                    if(outcome==TeleportService.Outcome.NOT_PERFORMED){preparedOutcome(plan,pending,false);return;}
                    try {
                        checkpoints.checkpoint(original).whenComplete((saved,saveFailure)->{
                            if(saveFailure!=null||saved==null||saved.status()!=dev.veyra.api.CheckpointResult.Status.SAVED){pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);return;}
                            index().named(ticket.portal()).ifPresent(portal->effects(original,portal));
                            preparedOutcome(plan,pending,true);
                        });
                    }catch(RuntimeException unavailable){pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);}
                });
            },()->pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN))==null)pending.result().complete(InventoryTransferApi.Preparation.UNKNOWN);
        });
        return pending.result();
    }
    private void preparedOutcome(TransferPlan plan,Preparing pending,boolean moved) {
        if(!moved)messages.send(pending.original(),"portal.arrival-failed");
        workers.submit(()->{
            if(!repository.finishTransferArrival(plan,moved))return false;
            repository.finishPortalTrip(plan.id(),moved?"arrived":"not_performed");return true;
        }).whenComplete((saved,failure)->pending.result().complete(failure==null&&Boolean.TRUE.equals(saved)?InventoryTransferApi.Preparation.READY:InventoryTransferApi.Preparation.UNKNOWN));
    }

    private void withdraw(TravelTicket ticket, boolean provenNotSent) {
        workers.submit(() -> repository.withdrawTicket(ticket.id(), provenNotSent)).exceptionally(failure -> {
            messages.warn("diagnostic.travel-ticket-withdraw");
            return false;
        });
    }

    private static boolean authorized(Player player, Portal portal) {
        return player.isOp() || player.hasPermission("atlas.portal.use")
                && (!portal.restricted() || player.hasPermission(portal.permission()));
    }

    /** Legacy unbound tickets are retained as evidence; only Controller preparation may relocate a join. */
    public CompletableFuture<Optional<Travel>> arrive(Player player) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private void finish(UUID operation, String outcome, Travel resultValue, CompletableFuture<Travel> result) {
        if (statistics != null && !statistics.retainsPortal(operation)) { result.complete(resultValue); return; }
        workers.submit(() -> { repository.finishPortalTrip(operation, outcome); return null; })
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        AtlasStatistics current = statistics;
                        if (current != null) current.observeGap("portal_terminal_write_failed", "atlas_logbook_portal_trips",
                                "portal_trip", null);
                    }
                    result.complete(resultValue);
                });
    }

    private void unresolved(UUID operation, String cause) {
        if (statistics != null && !statistics.retainsPortal(operation)) return;
        workers.submit(() -> { repository.unresolvedPortalTrip(operation, cause); return null; }).exceptionally(failure -> {
            AtlasStatistics current = statistics;
            if (current != null) current.observeGap("portal_unresolved_gap_write_failed", "atlas_logbook_portal_trips",
                    "portal_trip", null);
            return null;
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
