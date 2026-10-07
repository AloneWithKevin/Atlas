package nl.pixelretreat.atlas.listener;

import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.service.PortalService;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.service.SelectionService;
import nl.pixelretreat.atlas.service.SelectorDeliveryService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Forced game modes, cross-server portal arrivals and per-player cleanup. */
public final class PlayerListener implements Listener {
    private final Plugin plugin;
    private final RulesService rules;
    private final PortalService portals;
    private final SelectionService selections;
    private final AtlasMessages messages;
    private final SelectorDeliveryService selectors;

    public PlayerListener(Plugin plugin, RulesService rules, PortalService portals,
                          SelectionService selections, AtlasMessages messages) {
        this(plugin, rules, portals, selections, messages, null);
    }

    public PlayerListener(Plugin plugin, RulesService rules, PortalService portals,
                          SelectionService selections, AtlasMessages messages, SelectorDeliveryService selectors) {
        this.plugin = plugin;
        this.rules = rules;
        this.portals = portals;
        this.selections = selections;
        this.messages = messages;
        this.selectors = selectors;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        rules.applyGameMode(event.getPlayer());
        arrived(event.getPlayer());
    }

    /** Completes a cross-server portal trip if a travel ticket is waiting for this player. */
    public void arrived(Player player) {
        if (selectors != null) selectors.resume(player.getUniqueId()).thenAccept(result -> {
            if (result == SelectorDeliveryService.Result.NONE) return;
            messages.send(player, switch (result) {
                case GIVEN -> "selector.given";
                case OVERFLOW -> "selector.overflow";
                case PENDING, NONE -> "selector.pending";
            });
        });
        portals.arrive(player).whenComplete((travel, failure) -> {
            if (failure != null) {
                messages.warn("diagnostic.travel-ticket-read");
                messages.send(player, "portal.arrival-failed");
                return;
            }
            if (travel.isEmpty()) return;
            if (travel.get() != PortalService.Travel.ARRIVED) messages.send(player, "portal.arrival-failed");
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChangedWorld(PlayerChangedWorldEvent event) { rules.applyGameMode(event.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.clear(event.getPlayer().getUniqueId());
        portals.forget(event.getPlayer().getUniqueId());
    }
}
