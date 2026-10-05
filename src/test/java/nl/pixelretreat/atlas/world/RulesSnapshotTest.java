package nl.pixelretreat.atlas.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.api.AtlasRules;
import nl.pixelretreat.atlas.service.RulesService;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class RulesSnapshotTest {
    private static Map<AtlasFlag, Boolean> defaults() {
        Map<AtlasFlag, Boolean> defaults = new EnumMap<>(AtlasFlag.class);
        for (AtlasFlag flag : AtlasFlag.values()) defaults.put(flag, flag != AtlasFlag.KEEP_INVENTORY);
        return defaults;
    }

    @Test void overridesWinAndOtherwiseTheDefaultApplies() {
        var spawn = new WorldRecord("atlas:spawn", Optional.of(WorldGenerator.VOID), true, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(6000L), Optional.empty(),
                Map.of(AtlasFlag.PVP, false, AtlasFlag.KEEP_INVENTORY, true));
        var snapshot = new RulesSnapshot(Map.of(spawn.key(), spawn), defaults());
        assertFalse(snapshot.flag("atlas:spawn", AtlasFlag.PVP));
        assertTrue(snapshot.flag("atlas:spawn", AtlasFlag.KEEP_INVENTORY));
        assertTrue(snapshot.flag("atlas:spawn", AtlasFlag.HUNGER));
        assertTrue(snapshot.flag(WorldNames.OVERWORLD, AtlasFlag.PVP));
        assertFalse(snapshot.flag(WorldNames.OVERWORLD, AtlasFlag.KEEP_INVENTORY));
        assertTrue(snapshot.worldOrBuiltIn(WorldNames.NETHER).builtIn());
    }

    @Test void everyFlagNeedsADefault() {
        Map<AtlasFlag, Boolean> incomplete = new EnumMap<>(defaults());
        incomplete.remove(AtlasFlag.PORTALS);
        assertThrows(IllegalArgumentException.class, () -> new RulesSnapshot(Map.of(), incomplete));
    }

    @Test void flagKeysAreStableAndAcceptUnderscores() {
        assertEquals(Optional.of(AtlasFlag.PLUGIN_HOSTILE_MOBS), AtlasFlag.fromKey("plugin_hostile_mobs"));
        assertEquals(Optional.of(AtlasFlag.TIME_SKIP), AtlasFlag.fromKey("Time-Skip"));
        assertEquals(Optional.of(AtlasFlag.TRADING), AtlasFlag.fromKey("trading"));
        assertEquals(Optional.of(AtlasFlag.TRADING), AtlasFlag.fromKey("TRADING"));
        assertTrue(AtlasFlag.fromKey("fly").isEmpty());
        assertEquals(25, AtlasFlag.values().length);
    }

    @Test void tradingIsOnByDefaultAndAFalseOverrideAppliesPerWorld() {
        var arena = record("atlas:arena", Map.of(AtlasFlag.TRADING, false));
        var spawn = record("atlas:spawn", Map.of());
        var snapshot = new RulesSnapshot(Map.of(arena.key(), arena, spawn.key(), spawn), defaults());
        assertTrue(snapshot.defaultFlag(AtlasFlag.TRADING));
        assertFalse(snapshot.flag(arena.key(), AtlasFlag.TRADING));
        assertTrue(snapshot.flag(spawn.key(), AtlasFlag.TRADING), "a world without an override uses the default");
        assertTrue(snapshot.flag(WorldNames.OVERWORLD, AtlasFlag.TRADING));

        var cleared = new RulesSnapshot(Map.of(arena.key(), record("atlas:arena", Map.of())), defaults());
        assertTrue(cleared.flag(arena.key(), AtlasFlag.TRADING), "clearing the override returns to the default");
    }

    @Test void tradingTrueOverrideWinsOverAConfiguredFalseDefault() {
        Map<AtlasFlag, Boolean> tradingOff = defaults();
        tradingOff.put(AtlasFlag.TRADING, false);
        var arena = record("atlas:arena", Map.of(AtlasFlag.TRADING, true));
        var spawn = record("atlas:spawn", Map.of());
        var snapshot = new RulesSnapshot(Map.of(arena.key(), arena, spawn.key(), spawn), tradingOff);
        assertFalse(snapshot.defaultFlag(AtlasFlag.TRADING));
        assertTrue(snapshot.flag(arena.key(), AtlasFlag.TRADING), "an explicit true override enables trading");
        assertFalse(snapshot.flag(spawn.key(), AtlasFlag.TRADING),
                "a world without an override stays at the configured default");

        var cleared = new RulesSnapshot(Map.of(arena.key(), record("atlas:arena", Map.of())), tradingOff);
        assertFalse(cleared.flag(arena.key(), AtlasFlag.TRADING),
                "clearing the true override returns to the configured false default");
    }

    private static WorldRecord record(String key, Map<AtlasFlag, Boolean> flags) {
        return new WorldRecord(key, Optional.of(WorldGenerator.VOID), true, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), flags);
    }

    @Test void pluginSpawnsUseTheirOwnFlags() {
        assertEquals(AtlasFlag.HOSTILE_MOBS, RulesService.controllingFlag(EntityType.ZOMBIE, AtlasRules.SpawnOrigin.GAME));
        assertEquals(AtlasFlag.PLUGIN_HOSTILE_MOBS, RulesService.controllingFlag(EntityType.ZOMBIE, AtlasRules.SpawnOrigin.PLUGIN));
        assertEquals(AtlasFlag.FRIENDLY_MOBS, RulesService.controllingFlag(EntityType.COW, AtlasRules.SpawnOrigin.GAME));
        assertEquals(AtlasFlag.PLUGIN_FRIENDLY_MOBS, RulesService.controllingFlag(EntityType.AXOLOTL, AtlasRules.SpawnOrigin.PLUGIN));
        assertNull(RulesService.controllingFlag(EntityType.ARMOR_STAND, AtlasRules.SpawnOrigin.GAME));
    }
}
