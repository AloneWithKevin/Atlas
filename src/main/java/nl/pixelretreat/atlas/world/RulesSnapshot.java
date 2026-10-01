package nl.pixelretreat.atlas.world;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import nl.pixelretreat.atlas.api.AtlasFlag;

/**
 * Immutable view of every world's rules on this server. Listeners on any thread read the current
 * snapshot without locking; every change publishes a complete new snapshot.
 */
public final class RulesSnapshot {
    private final Map<String, WorldRecord> worlds;
    private final Map<AtlasFlag, Boolean> defaults;

    public RulesSnapshot(Map<String, WorldRecord> worlds, Map<AtlasFlag, Boolean> defaults) {
        this.worlds = Map.copyOf(worlds);
        if (!defaults.keySet().containsAll(java.util.EnumSet.allOf(AtlasFlag.class))) {
            throw new IllegalArgumentException("Every flag needs a default value");
        }
        this.defaults = Map.copyOf(new EnumMap<>(defaults));
    }

    /** The effective flag: the world's override, otherwise the configured default. */
    public boolean flag(String worldKey, AtlasFlag flag) {
        WorldRecord record = worlds.get(worldKey);
        if (record != null) {
            Boolean override = record.flags().get(flag);
            if (override != null) return override;
        }
        return defaults.get(flag);
    }

    /** The stored record, if the world has one. */
    public Optional<WorldRecord> world(String key) { return Optional.ofNullable(worlds.get(key)); }

    /** The stored record or an empty one for a vanilla dimension. */
    public WorldRecord worldOrBuiltIn(String key) {
        WorldRecord record = worlds.get(key);
        return record != null ? record : WorldRecord.builtIn(key);
    }

    /** All stored records. */
    public Collection<WorldRecord> worlds() { return worlds.values(); }

    /** The configured default of a flag. */
    public boolean defaultFlag(AtlasFlag flag) { return defaults.get(flag); }
}
