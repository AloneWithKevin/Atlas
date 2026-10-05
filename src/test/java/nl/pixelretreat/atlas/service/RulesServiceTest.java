package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.WorldGenerator;
import nl.pixelretreat.atlas.world.WorldRecord;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** The AtlasRules view must fail closed before readiness and follow published and reloaded rules. */
class RulesServiceTest {
    @Test void tradingIsRefusedBeforeTheFirstRulesLoad() {
        RulesService rules = new RulesService(mock(Plugin.class), mock(AtlasRepository.class), null, config(true));
        assertFalse(rules.ready());
        assertFalse(rules.flagEnabled(world(), AtlasFlag.TRADING));
    }

    @Test void configuredDefaultDrivesTradingWithoutAnOverride() {
        RulesService rules = new RulesService(mock(Plugin.class), mock(AtlasRepository.class), null, config(false));
        rules.publish(Map.of());
        assertTrue(rules.ready());
        assertFalse(rules.flagEnabled(world(), AtlasFlag.TRADING));

        rules.publish(Map.of("atlas:arena", record(Map.of(AtlasFlag.TRADING, true))));
        assertTrue(rules.flagEnabled(world(), AtlasFlag.TRADING),
                "an explicit true override wins over the configured false default");

        rules.publish(Map.of("atlas:arena", record(Map.of())));
        assertFalse(rules.flagEnabled(world(), AtlasFlag.TRADING),
                "clearing the override returns to the configured false default");
    }

    @Test void publishedAndReloadedRulesDriveTrading() throws Exception {
        AtlasRepository repository = mock(AtlasRepository.class);
        when(repository.loadWorlds()).thenReturn(Map.of("atlas:arena", record(Map.of(AtlasFlag.TRADING, false))));
        try (AtlasWorkers workers = new AtlasWorkers(1, 4)) {
            RulesService rules = new RulesService(mock(Plugin.class), repository, workers, config(true));
            World world = world();
            rules.publish(repository.loadWorlds());
            assertTrue(rules.ready());
            assertFalse(rules.flagEnabled(world, AtlasFlag.TRADING));

            when(repository.loadWorlds()).thenReturn(Map.of("atlas:arena", record(Map.of())));
            rules.reload().join();
            assertTrue(rules.flagEnabled(world, AtlasFlag.TRADING),
                    "clearing the override returns to the configured default after a reload");

            when(repository.loadWorlds()).thenReturn(Map.of());
            rules.reload().join();
            assertTrue(rules.flagEnabled(world, AtlasFlag.TRADING));
        }
    }

    private static World world() {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(NamespacedKey.fromString("atlas:arena"));
        return world;
    }

    private static WorldRecord record(Map<AtlasFlag, Boolean> flags) {
        return new WorldRecord("atlas:arena", Optional.of(WorldGenerator.VOID), true, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), flags);
    }

    private static AtlasConfig config(boolean trading) {
        Map<AtlasFlag, Boolean> flags = new EnumMap<>(AtlasFlag.class);
        for (AtlasFlag flag : AtlasFlag.values()) flags.put(flag, flag != AtlasFlag.KEEP_INVENTORY);
        flags.put(AtlasFlag.TRADING, trading);
        return new AtlasConfig("EU", Map.of("EU", "survival", "NA", "survival-na"),
                "127.0.0.1", 3306, "pixelretreat_veyra", "atlas", "", "disable",
                121, 16, 2500, 5, 4096, 1024, flags, 1, 16);
    }
}
