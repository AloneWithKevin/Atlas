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
        assertTrue(AtlasFlag.fromKey("fly").isEmpty());
        assertEquals(24, AtlasFlag.values().length);
    }

    @Test void pluginSpawnsUseTheirOwnFlags() {
        assertEquals(AtlasFlag.HOSTILE_MOBS, RulesService.controllingFlag(EntityType.ZOMBIE, AtlasRules.SpawnOrigin.GAME));
        assertEquals(AtlasFlag.PLUGIN_HOSTILE_MOBS, RulesService.controllingFlag(EntityType.ZOMBIE, AtlasRules.SpawnOrigin.PLUGIN));
        assertEquals(AtlasFlag.FRIENDLY_MOBS, RulesService.controllingFlag(EntityType.COW, AtlasRules.SpawnOrigin.GAME));
        assertEquals(AtlasFlag.PLUGIN_FRIENDLY_MOBS, RulesService.controllingFlag(EntityType.AXOLOTL, AtlasRules.SpawnOrigin.PLUGIN));
        assertNull(RulesService.controllingFlag(EntityType.ARMOR_STAND, AtlasRules.SpawnOrigin.GAME));
    }
}
