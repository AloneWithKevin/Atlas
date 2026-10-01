package nl.pixelretreat.atlas.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable lookup of portals by world and chunk, so a player move checks only the few portals in
 * the chunk being entered instead of every portal on the server.
 */
public final class PortalIndex {
    public static final PortalIndex EMPTY = new PortalIndex(List.of());

    private final Map<String, Portal> byName;
    private final Map<String, Map<Long, List<Portal>>> byChunk;

    public PortalIndex(Collection<Portal> portals) {
        Map<String, Portal> names = new HashMap<>();
        Map<String, Map<Long, List<Portal>>> chunks = new HashMap<>();
        for (Portal portal : portals) {
            names.put(portal.name(), portal);
            Map<Long, List<Portal>> world = chunks.computeIfAbsent(portal.world(), ignored -> new HashMap<>());
            for (int cx = portal.minX() >> 4; cx <= portal.maxX() >> 4; cx++) {
                for (int cz = portal.minZ() >> 4; cz <= portal.maxZ() >> 4; cz++) {
                    world.computeIfAbsent(chunkKey(cx, cz), ignored -> new ArrayList<>()).add(portal);
                }
            }
        }
        chunks.replaceAll((world, map) -> {
            map.replaceAll((key, list) -> List.copyOf(list));
            return Map.copyOf(map);
        });
        this.byName = Map.copyOf(names);
        this.byChunk = Map.copyOf(chunks);
    }

    /** The portal at a block position, if any. */
    public Optional<Portal> at(String world, int x, int y, int z) {
        Map<Long, List<Portal>> chunks = byChunk.get(world);
        if (chunks == null) return Optional.empty();
        List<Portal> candidates = chunks.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null) return Optional.empty();
        for (Portal portal : candidates) if (portal.contains(world, x, y, z)) return Optional.of(portal);
        return Optional.empty();
    }

    /** The portal with this name. */
    public Optional<Portal> named(String name) { return Optional.ofNullable(byName.get(name)); }

    /** All portals. */
    public Collection<Portal> all() { return byName.values(); }

    /** Whether any portal stands in the world. */
    public boolean anyIn(String world) { return byChunk.containsKey(world); }

    /** Whether the world has a portal in this chunk; a cheap pre-check for hot events. */
    public boolean anyInChunk(String world, int chunkX, int chunkZ) {
        Map<Long, List<Portal>> chunks = byChunk.get(world);
        return chunks != null && chunks.containsKey(chunkKey(chunkX, chunkZ));
    }

    private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
