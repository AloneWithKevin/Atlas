package nl.pixelretreat.atlas.api;

import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/** Toolbox's optional observation of an original local Atlas world change. */
@FunctionalInterface
public interface AtlasTeleportObserver {
    /**
     * Called on the original player's owner immediately before Atlas dispatches the move.
     * Capture the departure here without blocking. The read-only stage completes with true
     * only for a proved arrival. Completion may run on a different thread.
     */
    void departing(Player player, CompletionStage<Boolean> arrived);
}
