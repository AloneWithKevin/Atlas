package nl.pixelretreat.atlas.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import nl.pixelretreat.atlas.service.RulesService;
import org.bukkit.World;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.junit.jupiter.api.Test;

class WorldRulesListenerTest {
    @Test void naturalWeatherChangesAreRefusedUntilRulesAreReady() {
        RulesService rules = mock(RulesService.class);
        when(rules.snapshot()).thenReturn(null);
        WorldRulesListener listener = new WorldRulesListener(rules);
        World world = mock(World.class);

        var weather = new WeatherChangeEvent(world, true, WeatherChangeEvent.Cause.NATURAL);
        var thunder = new ThunderChangeEvent(world, true, ThunderChangeEvent.Cause.NATURAL);
        listener.onWeatherChange(weather);
        listener.onThunderChange(thunder);

        assertTrue(weather.isCancelled());
        assertTrue(thunder.isCancelled());
    }

    @Test void pluginWeatherChangesRemainAllowedBeforeRulesAreReady() {
        RulesService rules = mock(RulesService.class);
        when(rules.snapshot()).thenReturn(null);
        WorldRulesListener listener = new WorldRulesListener(rules);

        var weather = new WeatherChangeEvent(mock(World.class), true, WeatherChangeEvent.Cause.PLUGIN);
        var thunder = new ThunderChangeEvent(mock(World.class), true, ThunderChangeEvent.Cause.PLUGIN);
        listener.onWeatherChange(weather);
        listener.onThunderChange(thunder);

        assertFalse(weather.isCancelled());
        assertFalse(thunder.isCancelled());
    }

    @Test void nightSkipIsRefusedUntilRulesAreReady() {
        RulesService rules = mock(RulesService.class);
        when(rules.snapshot()).thenReturn(null);
        var event = new TimeSkipEvent(mock(World.class), ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 12_000L);

        new WorldRulesListener(rules).onTimeSkip(event);

        assertTrue(event.isCancelled());
    }
}
