package nl.pixelretreat.atlas.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable portal bounds grouped by world. Bounds are retained once instead of allocating an
 * entry for every covered chunk; even extreme console coordinates cannot expand the index.
 */
public final class PortalIndex {
    public static final PortalIndex EMPTY = new PortalIndex(List.of());

    private final Map<String, Portal> byName;
    private final Map<String, List<Portal>> byWorld;

    public PortalIndex(Collection<Portal> portals) {
        Map<String, Portal> names = new HashMap<>();
        Map<String, List<Portal>> worlds = new HashMap<>();
        for (Portal portal : portals) {
            names.put(portal.name(), portal);
            worlds.computeIfAbsent(portal.world(), ignored -> new ArrayList<>()).add(portal);
        }
        worlds.replaceAll((world, portalsInWorld) -> List.copyOf(portalsInWorld));
        this.byName = Map.copyOf(names);
        this.byWorld = Map.copyOf(worlds);
    }

    /** The portal at a block position, if any. */
    public Optional<Portal> at(String world, int x, int y, int z) {
        List<Portal> candidates = byWorld.get(world);
        if (candidates == null) return Optional.empty();
        for (Portal portal : candidates) if (portal.contains(world, x, y, z)) return Optional.of(portal);
        return Optional.empty();
    }

    /** The portal with this name. */
    public Optional<Portal> named(String name) { return Optional.ofNullable(byName.get(name)); }

    /** All portals. */
    public Collection<Portal> all() { return byName.values(); }

    /** Whether any portal stands in the world. */
    public boolean anyIn(String world) { return byWorld.containsKey(world); }

    /** Whether the world has a portal in this chunk; a cheap pre-check for hot events. */
    public boolean anyInChunk(String world, int chunkX, int chunkZ) {
        List<Portal> portals = byWorld.get(world);
        if (portals == null) return false;
        return portals.stream().anyMatch(portal -> chunkX >= (portal.minX() >> 4) && chunkX <= (portal.maxX() >> 4)
                && chunkZ >= (portal.minZ() >> 4) && chunkZ <= (portal.maxZ() >> 4));
    }
}
