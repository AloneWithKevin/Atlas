package nl.pixelretreat.atlas.world;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Folder work queued for the next server start, when the affected worlds are not loaded yet.
 *
 * @param world  the world whose folder changes
 * @param source the world copied from, for {@link Kind#CLONE} and {@link Kind#RESET}
 */
public record PendingOperation(long id, Kind kind, String world, Optional<String> source,
                               UUID requestedBy, Instant requestedAt) {
    /** What happens to the folder. */
    public enum Kind {
        /** Remove the folder of a world that is no longer declared. */
        DELETE,
        /** Copy the source folder into the new world's folder. */
        CLONE,
        /** Replace the world's folder with a fresh copy of the source. */
        RESET
    }
}
