package nl.pixelretreat.atlas.world;

import java.util.Map;
import java.util.Optional;
import nl.pixelretreat.atlas.api.AtlasFlag;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;

/**
 * One world's stored data on this server. Vanilla dimensions get a record as soon as staff change
 * one of their rules; Atlas worlds have one from creation. Absent settings mean "leave vanilla alone".
 *
 * @param key        dimension key, for example {@code atlas:arena}
 * @param generator  generator of an Atlas world; empty for the vanilla dimensions
 * @param enabled    whether the world is declared in the Atlas data pack (loaded after a restart)
 * @param flags      flag overrides; flags not present use the configured default
 */
public record WorldRecord(
        String key, Optional<WorldGenerator> generator, boolean enabled, Optional<String> resetSource,
        Optional<SpawnPoint> spawn, Optional<Difficulty> difficulty, Optional<GameMode> gameMode,
        Optional<Long> fixedTime, Optional<WeatherMode> weather, Map<AtlasFlag, Boolean> flags) {

    public WorldRecord {
        flags = Map.copyOf(flags);
    }

    /** A record for a vanilla dimension that has no stored settings yet. */
    public static WorldRecord builtIn(String key) {
        return new WorldRecord(key, Optional.empty(), true, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
    }

    /** Whether this is one of the three vanilla dimensions. */
    public boolean builtIn() { return WorldNames.builtIn(key); }
}
