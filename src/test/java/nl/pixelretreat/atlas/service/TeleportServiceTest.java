package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import nl.pixelretreat.atlas.api.AtlasTeleportObserver;
import nl.pixelretreat.atlas.world.SpawnPoint;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.junit.jupiter.api.Test;

class TeleportServiceTest {
    @Test void localCrossWorldDepartureReportsOnlyProvenNativeArrival() {
        var plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        var toolbox = mock(Plugin.class);
        when(toolbox.getName()).thenReturn("Toolbox");
        when(toolbox.isEnabled()).thenReturn(true);
        var player = mock(Player.class, RETURNS_DEEP_STUBS);
        when(player.isOnline()).thenReturn(true);
        when(player.getScheduler().run(any(), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class));
            return mock(ScheduledTask.class);
        });
        var from = mock(World.class);
        var to = mock(World.class);
        when(player.getWorld()).thenReturn(from);
        when(player.getLocation()).thenReturn(new Location(from, 14.5, 64, 17, 30, 4));
        var observation = new AtomicReference<CompletionStage<Boolean>>();
        AtlasTeleportObserver observer = (original, arrival) -> {
            assertSame(player, original);
            assertSame(from, original.getWorld());
            observation.set(arrival);
        };
        when(plugin.getServer().getServicesManager().getRegistration(AtlasTeleportObserver.class))
                .thenReturn(new RegisteredServiceProvider<>(AtlasTeleportObserver.class, observer, ServicePriority.Normal, toolbox));
        var moved = new AtomicReference<CompletableFuture<Boolean>>();
        when(player.teleportAsync(any(Location.class), eq(TeleportCause.PLUGIN))).thenAnswer(call -> {
            var pending = new CompletableFuture<Boolean>();
            moved.set(pending);
            return pending;
        });
        var service = new TeleportService(plugin, null, null);
        var destination = Optional.of(new SpawnPoint(1, 64, 1, 0, 0));
        var arrival = service.teleportLocal(player, to, destination, () -> true);
        assertFalse(observation.get().toCompletableFuture().isDone());
        moved.get().complete(true);
        assertEquals(TeleportService.Outcome.ARRIVED, arrival.join());
        assertTrue(observation.get().toCompletableFuture().join());

        var refused = service.teleportLocal(player, to, destination, () -> true);
        moved.get().complete(false);
        assertEquals(TeleportService.Outcome.NOT_PERFORMED, refused.join());
        assertFalse(observation.get().toCompletableFuture().join());

        var uncertain = service.teleportLocal(player, to, destination, () -> true);
        moved.get().completeExceptionally(new IllegalStateException("lost reply"));
        assertEquals(TeleportService.Outcome.UNKNOWN, uncertain.join());
        assertFalse(observation.get().toCompletableFuture().join());

        observation.set(null);
        var sameWorld = service.teleportLocal(player, from, destination, () -> true);
        moved.get().complete(true);
        assertEquals(TeleportService.Outcome.ARRIVED, sameWorld.join());
        assertNull(observation.get());
        var internal = service.teleportDetailed(player, to, destination, () -> true);
        moved.get().complete(true);
        assertEquals(TeleportService.Outcome.ARRIVED, internal.join());
        assertNull(observation.get());
    }

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
        var service = new TeleportService(mock(Plugin.class,RETURNS_DEEP_STUBS), rules, null);
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
        var result = new TeleportService(mock(Plugin.class,RETURNS_DEEP_STUBS), null, null).teleportDetailed(player, mock(World.class),
                Optional.of(new SpawnPoint(0, 64, 0, 0, 0)));
        assertEquals(TeleportService.Outcome.UNKNOWN, result.join());
        verify(player, times(1)).teleportAsync(any(org.bukkit.Location.class), any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class));
    }
    @Test void cancelledWorldAdmissionRefusesBeforeDismountOrNativeTeleport() {
        var plugin=mock(Plugin.class,RETURNS_DEEP_STUBS);var player=mock(Player.class);when(player.isOnline()).thenReturn(true);
        var scheduler=mock(EntityScheduler.class);when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(any(),any(),any())).thenAnswer(call->{((Consumer<ScheduledTask>)call.getArgument(1)).accept(mock(ScheduledTask.class));return mock(ScheduledTask.class);});
        var manager=plugin.getServer().getPluginManager();
        doAnswer(call->{call.getArgument(0,org.bukkit.event.player.PlayerTeleportEvent.class).setCancelled(true);return null;}).when(manager).callEvent(any());
        var result=new TeleportService(plugin,null,null).teleportDetailed(player,mock(World.class),Optional.of(new SpawnPoint(0,64,0,0,0)));
        assertEquals(TeleportService.Outcome.NOT_PERFORMED,result.join());
        verify(player,never()).teleportAsync(any(org.bukkit.Location.class),any(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class));verify(player,never()).leaveVehicle();verify(player,never()).eject();
    }
}
