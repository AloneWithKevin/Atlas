package nl.pixelretreat.atlas.portal;

import java.util.Optional;
import nl.pixelretreat.atlas.world.SpawnPoint;

/**
 * Where a portal sends players.
 *
 * @param region the other Postbox region for {@link Kind#SERVER}; empty for local targets
 * @param world  the destination world key on the destination server
 * @param point  an exact destination; empty means the destination world's spawn
 */
public record PortalTarget(Kind kind, Optional<String> region, String world, Optional<SpawnPoint> point) {
    /** The kind of destination. */
    public enum Kind {
        /** The spawn of a world on this server. */
        SPAWN,
        /** An exact location on this server. */
        LOCATION,
        /** A world (spawn or exact location) on the other server, reached through the proxy. */
        SERVER
    }

    public PortalTarget {
        if (kind == Kind.SERVER && region.isEmpty()) throw new IllegalArgumentException("Server target needs a region");
        if (kind != Kind.SERVER && region.isPresent()) throw new IllegalArgumentException("Local target has no region");
        if (kind == Kind.LOCATION && point.isEmpty()) throw new IllegalArgumentException("Location target needs a point");
        if (kind == Kind.SPAWN && point.isPresent()) throw new IllegalArgumentException("Spawn target has no point");
    }

    /** A local spawn target. */
    public static PortalTarget spawn(String world) {
        return new PortalTarget(Kind.SPAWN, Optional.empty(), world, Optional.empty());
    }
}
