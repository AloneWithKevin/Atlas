package nl.pixelretreat.atlas.listener;

import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.service.PortalService;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.service.SelectionService;
import nl.pixelretreat.atlas.statistics.AtlasStatistics;
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
    private final AtlasStatistics statistics;

    public PlayerListener(Plugin plugin, RulesService rules, PortalService portals,
                          SelectionService selections, AtlasMessages messages) {
        this(plugin, rules, portals, selections, messages, null);
    }

    public PlayerListener(Plugin plugin, RulesService rules, PortalService portals,
                          SelectionService selections, AtlasMessages messages, AtlasStatistics statistics) {
        this.plugin = plugin;
        this.rules = rules;
        this.portals = portals;
        this.selections = selections;
        this.messages = messages;
        this.statistics = statistics;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (statistics != null) statistics.startVisit(player.getUniqueId(), RulesService.key(player.getWorld()), "join");
        rules.applyGameMode(event.getPlayer());
        arrived(event.getPlayer());
    }

    /** Starts measurement at the current observation boundary, including players already online at readiness. */
    public void observeWorldVisit(Player player) {
        if (statistics != null) statistics.startVisit(player.getUniqueId(), RulesService.key(player.getWorld()),
                "readiness_observation");
    }

    /** Completes a cross-server portal trip if a travel ticket is waiting for this player. */
    public void arrived(Player player) {
        portals.arrive(player).whenComplete((travel, failure) -> {
            portals.onPlayerScheduler(player, current -> {
                if (failure != null) {
                    messages.warn("diagnostic.travel-ticket-read");
                    messages.send(current, "portal.arrival-failed");
                    return;
                }
                if (travel.isEmpty()) return;
                if (travel.get() != PortalService.Travel.ARRIVED) messages.send(current, "portal.arrival-failed");
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        if (statistics != null) statistics.rotateVisit(event.getPlayer().getUniqueId(),
                RulesService.key(event.getPlayer().getWorld()));
        rules.applyGameMode(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (statistics != null) statistics.endVisit(event.getPlayer().getUniqueId(), "quit");
        selections.clear(event.getPlayer().getUniqueId());
        portals.forget(event.getPlayer());
    }
}
