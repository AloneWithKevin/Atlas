package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import nl.pixelretreat.atlas.world.SpawnPoint;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.junit.jupiter.api.Test;

class TeleportServiceTest {
    @Test void unsafeUsesNativeSpawnWithoutGroundSearchButRechecksAdmission() {
        var player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        var scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        var callback = new AtomicReference<Consumer<ScheduledTask>>();
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            callback.set(call.getArgument(1)); return mock(ScheduledTask.class);
        });
        var world = mock(World.class);
        var spawn = new org.bukkit.Location(world, 4.5, 90, -8.5, 45, 12);
        when(world.getSpawnLocation()).thenReturn(spawn);
        var rules = mock(RulesService.class);
        var service = new TeleportService(mock(Plugin.class), rules, null);
        when(player.teleportAsync(eq(spawn), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(true));
        var admitted = new AtomicBoolean(true);
        var first = service.teleportUnsafe(player, world, admitted::get);
        callback.get().accept(mock(ScheduledTask.class));
        assertEquals(TeleportService.Outcome.ARRIVED, first.join());
        var revoked = service.teleportUnsafe(player, world, admitted::get);
        admitted.set(false);
        callback.get().accept(mock(ScheduledTask.class));
        assertEquals(TeleportService.Outcome.NOT_PERFORMED, revoked.join());
        verify(player, times(1)).teleportAsync(eq(spawn), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class));
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }
    @Test void revokedAdmissionBeforeTheOriginalEntityCallbackPreventsTheTeleport() {
        var player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        var scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        var callback = new AtomicReference<Consumer<ScheduledTask>>();
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            callback.set(call.getArgument(1)); return mock(ScheduledTask.class);
        });
        var admitted = new AtomicBoolean(true);
        var service = new TeleportService(mock(Plugin.class), null, null);
        var result = service.teleportDetailed(player, mock(World.class), Optional.of(new SpawnPoint(0, 64, 0, 0, 0)), admitted::get);
        assertFalse(result.isDone());
        admitted.set(false);
        callback.get().accept(mock(ScheduledTask.class));
        assertEquals(TeleportService.Outcome.NOT_PERFORMED, result.join());
        verify(player, never()).teleportAsync(any(org.bukkit.Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class));
    }

    @Test void lostProviderReplyRemainsUnknownAndDoesNotScheduleAnotherTeleport() {
        var player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        var scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            Consumer<ScheduledTask> callback = call.getArgument(1);
            callback.accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class);
        });
        when(player.teleportAsync(any(org.bukkit.Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("lost reply")));
        var result = new TeleportService(mock(Plugin.class), null, null).teleportDetailed(player, mock(World.class),
                Optional.of(new SpawnPoint(0, 64, 0, 0, 0)));
        assertEquals(TeleportService.Outcome.UNKNOWN, result.join());
        verify(player, times(1)).teleportAsync(any(org.bukkit.Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class));
    }
}
