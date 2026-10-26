package nl.pixelretreat.atlas.listener;

import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.statistics.WorldVisits;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Dynamic world IDs come from actual lifecycle events, never a hardcoded consumer list. */
public final class WorldVisitListener implements Listener {
    private final WorldVisits visits;
    public WorldVisitListener(WorldVisits visits) { this.visits = visits; }
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(WorldLoadEvent event) { visits.available(RulesService.key(event.getWorld()), true); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unloaded(WorldUnloadEvent event) { visits.available(RulesService.key(event.getWorld()), false); }
}
