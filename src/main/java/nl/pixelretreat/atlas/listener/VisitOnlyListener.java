package nl.pixelretreat.atlas.listener;

import java.util.Set;
import nl.pixelretreat.atlas.service.RulesService;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

/** Visitors may explore configured worlds. A dedicated permission allows ordinary gameplay there. */
public final class VisitOnlyListener implements Listener {
    private static final String EDIT_PERMISSION = "atlas.world.visit.edit";
    private final Set<String> worlds;
    private static final NamespacedKey PREVIOUS_MODE_KEY = new NamespacedKey("atlas", "visit_previous_game_mode");

    public VisitOnlyListener(Set<String> worlds) {
        this.worlds = Set.copyOf(worlds);
    }

    private boolean isVisitor(Player player) {
        return worlds.contains(RulesService.key(player.getWorld())) && !player.hasPermission(EDIT_PERMISSION);
    }

    private void protect(Cancellable event, Player player) {
        if (isVisitor(player)) event.setCancelled(true);
    }

    private void updateGameMode(Player player) {
        var data = player.getPersistentDataContainer();
        String previous = data.get(PREVIOUS_MODE_KEY, PersistentDataType.STRING);
        if (isVisitor(player)) {
            if (player.getGameMode() != GameMode.SURVIVAL) {
                if (previous == null) data.set(PREVIOUS_MODE_KEY, PersistentDataType.STRING, player.getGameMode().name());
                player.setGameMode(GameMode.SURVIVAL);
            }
        } else if (previous != null) {
            if (player.getGameMode() == GameMode.SURVIVAL) {
                GameMode mode = switch (previous) {
                    case "CREATIVE" -> GameMode.CREATIVE;
                    case "ADVENTURE" -> GameMode.ADVENTURE;
                    case "SPECTATOR" -> GameMode.SPECTATOR;
                    default -> GameMode.SURVIVAL;
                };
                player.setGameMode(mode);
            }
            if (player.getGameMode().name().equals(previous) || player.getGameMode() != GameMode.SURVIVAL) {
                data.remove(PREVIOUS_MODE_KEY);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) { updateGameMode(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChangedWorld(PlayerChangedWorldEvent event) { updateGameMode(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SURVIVAL) protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) { protect(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) { protect(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) protect(event, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { protect(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { protect(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { protect(event, event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) protect(event, player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamageSource().getCausingEntity() instanceof Player player) protect(event, player);
    }
}

