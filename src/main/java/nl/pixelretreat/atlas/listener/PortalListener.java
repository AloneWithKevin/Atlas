package nl.pixelretreat.atlas.listener;

import java.util.Map;
import java.util.Optional;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.service.PortalService;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import nl.pixelretreat.atlas.world.WorldNames;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;

/**
 * Sends players through Atlas portals when they walk into one, and stops vanilla portal behaviour
 * (travel and block physics) inside Atlas portals so only Atlas decides the destination.
 */
public final class PortalListener implements Listener {
    public static final String USE = "atlas.portal.use";

    private final RulesService rules;
    private final PortalService portals;
    private final AtlasMessages messages;

    public PortalListener(RulesService rules, PortalService portals, AtlasMessages messages) {
        this.rules = rules;
        this.portals = portals;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to.getBlockX() == event.getFrom().getBlockX() && to.getBlockY() == event.getFrom().getBlockY()
                && to.getBlockZ() == event.getFrom().getBlockZ()) return;
        String world = RulesService.key(to.getWorld());
        Optional<Portal> found = portals.index().at(world, to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (found.isEmpty()) return;
        Portal portal = found.get();
        Player player = event.getPlayer();
        RulesSnapshot snapshot = rules.snapshot();
        if (snapshot == null || !snapshot.flag(world, AtlasFlag.PORTALS)) return;
        if (!player.hasPermission(USE) || portal.restricted() && !player.hasPermission(portal.permission())) return;
        if (!portals.startCooldown(player, portal)) return;
        portals.travel(player, portal).whenComplete((travel, failure) -> {
            if (failure != null) { messages.send(player, "portal.failed"); return; }
            switch (travel) {
                case ARRIVED -> { }
                case SWITCHING -> messages.send(player, "portal.switching",
                        Map.of("region", portal.target().region().orElse("")));
                case TARGET_MISSING -> messages.send(player, "portal.target-missing",
                        Map.of("world", WorldNames.shortName(portal.target().world())));
                case FAILED -> messages.send(player, "portal.failed");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnterVanillaPortal(EntityPortalEnterEvent event) {
        if (inside(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (inside(event.getFrom())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (inside(event.getFrom())) event.setCancelled(true);
    }

    /** Keeps filled portal blocks (nether portal, end gateway) from breaking on block updates. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Material type = event.getBlock().getType();
        if (type != Material.NETHER_PORTAL && type != Material.END_GATEWAY && type != Material.END_PORTAL) return;
        Block block = event.getBlock();
        String world = RulesService.key(block.getWorld());
        if (!portals.index().anyInChunk(world, block.getX() >> 4, block.getZ() >> 4)) return;
        portals.index().at(world, block.getX(), block.getY(), block.getZ())
                .filter(portal -> portal.fillBlock().map(type.name()::equals).orElse(false))
                .ifPresent(portal -> event.setCancelled(true));
    }

    private boolean inside(Location location) {
        if (location == null || location.getWorld() == null) return false;
        return portals.index().at(RulesService.key(location.getWorld()),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()).isPresent();
    }
}
