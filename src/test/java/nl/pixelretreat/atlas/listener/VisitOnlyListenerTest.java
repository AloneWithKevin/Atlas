package nl.pixelretreat.atlas.listener;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitOnlyListenerTest {
    private final World archive = world("atlas", "survival-staff");
    private final World overworld = world("minecraft", "overworld");

    @Test
    void visitorsPlayInSurvivalAndRecoverTheirModeAfterLeaving() {
        var listener = new VisitOnlyListener(Set.of("atlas:survival-staff"));
        Player player = mock(Player.class);
        var world = new AtomicReference<>(archive);
        var mode = new AtomicReference<>(GameMode.ADVENTURE);
        var previous = new AtomicReference<String>();
        when(player.getWorld()).thenAnswer(ignored -> world.get());
        when(player.getGameMode()).thenAnswer(ignored -> mode.get());
        doAnswer(call -> { mode.set(call.getArgument(0)); return null; }).when(player).setGameMode(any());
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(), eq(PersistentDataType.STRING))).thenAnswer(ignored -> previous.get());
        doAnswer(call -> { previous.set(call.getArgument(2)); return null; }).when(data)
                .set(any(), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { previous.set(null); return null; }).when(data).remove(any());
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(player);
        listener.onJoin(join);
        listener.onJoin(join);
        assertEquals(GameMode.SURVIVAL, mode.get());
        assertEquals("ADVENTURE", previous.get());

        var manualChange = mock(PlayerGameModeChangeEvent.class);
        when(manualChange.getPlayer()).thenReturn(player);
        when(manualChange.getNewGameMode()).thenReturn(GameMode.SPECTATOR);
        listener.onGameMode(manualChange);
        verify(manualChange).setCancelled(true);

        world.set(overworld);
        listener.onChangedWorld(new PlayerChangedWorldEvent(player, archive));
        assertEquals(GameMode.ADVENTURE, mode.get());
        assertNull(previous.get());
    }

    @Test
    void blockProtectionIsLimitedToVisitorsInConfiguredWorlds() {
        var listener = new VisitOnlyListener(Set.of("atlas:survival-staff"));
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(archive, archive, overworld);
        when(player.hasPermission("atlas.world.visit.edit")).thenReturn(false, true);
        BlockBreakEvent visitor = mock(BlockBreakEvent.class);
        BlockBreakEvent editor = mock(BlockBreakEvent.class);
        BlockBreakEvent outside = mock(BlockBreakEvent.class);
        when(visitor.getPlayer()).thenReturn(player);
        when(editor.getPlayer()).thenReturn(player);
        when(outside.getPlayer()).thenReturn(player);
        listener.onBreak(visitor);
        listener.onBreak(editor);
        listener.onBreak(outside);
        verify(visitor).setCancelled(true);
        verify(editor, never()).setCancelled(true);
        verify(outside, never()).setCancelled(true);
    }

    private static World world(String namespace, String name) {
        World world = mock(World.class);
        when(world.getKey()).thenReturn(new NamespacedKey(namespace, name));
        return world;
    }
}

