package nl.pixelretreat.atlas.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Two-corner block selections made with the Atlas selector, kept per player until they quit. */
public final class SelectionService {
    /** A selected block. */
    public record Corner(String world, int x, int y, int z) { }

    /** A complete selection inside one world, with ordered bounds. */
    public record Bounds(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }

    private record Selection(Corner first, Corner second) { }

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();

    /** Stores corner 1 (left click) or 2 (right click). */
    public void set(UUID player, int corner, Corner value) {
        selections.compute(player, (ignored, old) -> {
            Selection current = old == null ? new Selection(null, null) : old;
            return corner == 1 ? new Selection(value, current.second()) : new Selection(current.first(), value);
        });
    }

    /** The selection, if both corners are set in the same world. */
    public Optional<Bounds> bounds(UUID player) {
        Selection selection = selections.get(player);
        if (selection == null || selection.first() == null || selection.second() == null
                || !selection.first().world().equals(selection.second().world())) return Optional.empty();
        Corner a = selection.first();
        Corner b = selection.second();
        return Optional.of(new Bounds(a.world(), Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
    }

    /** Whether both corners are set but in different worlds. */
    public boolean splitAcrossWorlds(UUID player) {
        Selection selection = selections.get(player);
        return selection != null && selection.first() != null && selection.second() != null
                && !selection.first().world().equals(selection.second().world());
    }

    /** Forgets a player's selection. */
    public void clear(UUID player) { selections.remove(player); }
}
