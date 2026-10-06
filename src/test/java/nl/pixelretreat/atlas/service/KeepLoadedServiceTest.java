package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import nl.pixelretreat.atlas.keeploaded.KeepLoadedRegion;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class KeepLoadedServiceTest {
    private static KeepLoadedRegion region(String name, int min, int max) {
        return new KeepLoadedRegion(name, "atlas:arena", min, 0, max, 0);
    }
    private static class Fixture implements AutoCloseable {
        final Plugin plugin = mock(Plugin.class);
        final Server server = mock(Server.class);
        final World world = mock(World.class);
        final AtlasRepository repository = mock(AtlasRepository.class);
        final GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        final BlockingQueue<Consumer<ScheduledTask>> callbacks = new LinkedBlockingQueue<>();
        final AtlasWorkers workers = new AtlasWorkers(2, 8);
        final KeepLoadedService service = new KeepLoadedService(plugin, repository, workers);
        Fixture() {
            when(plugin.getServer()).thenReturn(server);
            when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
            when(server.getWorld(NamespacedKey.fromString("atlas:arena"))).thenReturn(world);
            when(server.getWorlds()).thenReturn(List.of(world));
            when(world.addPluginChunkTicket(anyInt(), anyInt(), eq(plugin))).thenReturn(true);
            when(scheduler.run(eq(plugin), any())).thenAnswer(call -> {
                callbacks.add(call.getArgument(1)); return mock(ScheduledTask.class);
            });
        }
        Consumer<ScheduledTask> next() throws Exception {
            var callback = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback); return callback;
        }
        @Override public void close() { workers.close(); }
    }
    @Test void completionWaitsForTicketsAndOverlapsHaveOneTicket() throws Exception {
        try (var f = new Fixture()) {
            when(f.repository.loadRegions()).thenReturn(List.of(region("a", 0, 1), region("b", 1, 2)));
            var first = f.service.reload(); var callback = f.next();
            assertFalse(first.isDone()); callback.accept(mock(ScheduledTask.class)); first.get(5, TimeUnit.SECONDS);
            assertEquals(3, f.service.ticketCount());
            verify(f.world, times(3)).addPluginChunkTicket(anyInt(), eq(0), eq(f.plugin));
            when(f.repository.loadRegions()).thenReturn(List.of(region("b", 1, 1)));
            var second = f.service.reload(); f.next().accept(mock(ScheduledTask.class)); second.get(5, TimeUnit.SECONDS);
            assertEquals(1, f.service.ticketCount());
            verify(f.world).removePluginChunkTicket(0, 0, f.plugin);
            verify(f.world).removePluginChunkTicket(2, 0, f.plugin);
        }
    }
    @Test void cleanupCancelsPendingCallbacksAndRefusesFurtherWrites() throws Exception {
        try (var f = new Fixture()) {
            when(f.repository.loadRegions()).thenReturn(List.of(region("a", 0, 0)));
            var pending = f.service.reload(); var late = f.next();
            f.service.releaseAll();
            assertThrows(CompletionException.class, pending::join);
            late.accept(mock(ScheduledTask.class));
            verify(f.world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
            verify(f.world).removePluginChunkTickets(f.plugin);
            assertEquals(0, f.service.ticketCount());
            assertThrows(CompletionException.class, () -> f.service.save(region("b", 1, 1)).join());
            assertThrows(CompletionException.class, () -> f.service.delete("a").join());
            verify(f.repository, never()).saveRegion(any());
            verify(f.repository, never()).deleteRegion(any());
        }
    }
    @Test void olderDatabaseReadCannotReplaceNewerSnapshot() throws Exception {
        try (var f = new Fixture()) {
            var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
            when(f.repository.loadRegions()).thenAnswer(call -> {
                entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); return List.of(region("old", 0, 0));
            }).thenReturn(List.of(region("new", 1, 1)));
            var old = f.service.reload();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var newer = f.service.reload(); f.next().accept(mock(ScheduledTask.class)); newer.get(5, TimeUnit.SECONDS);
            } finally { finish.countDown(); }
            old.get(5, TimeUnit.SECONDS);
            assertEquals("new", f.service.regions().getFirst().name());
            assertTrue(f.callbacks.isEmpty());
            verify(f.world, never()).addPluginChunkTicket(0, 0, f.plugin);
        }
    }
    @Test void schedulingAndCleanupFailuresStillSettleResults() throws Exception {
        try (var f = new Fixture()) {
            when(f.repository.loadRegions()).thenReturn(List.of(region("a", 0, 0)));
            when(f.scheduler.run(eq(f.plugin), any())).thenThrow(new IllegalStateException("rejected"));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> f.service.reload().get(5, TimeUnit.SECONDS)).getCause());
        }
        try (var f = new Fixture()) {
            when(f.repository.loadRegions()).thenReturn(List.of(region("a", 0, 0)));
            var pending = f.service.reload(); var late = f.next();
            doThrow(new IllegalStateException("cleanup")).when(f.world).removePluginChunkTickets(f.plugin);
            assertThrows(IllegalStateException.class, f.service::releaseAll);
            assertTrue(pending.isDone()); late.accept(mock(ScheduledTask.class));
            verify(f.world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
        }
    }
    @Test void ticketFailureIsReportedAndExtremeCoordinateIsFinite() throws Exception {
        try (var f = new Fixture()) {
            when(f.repository.loadRegions()).thenReturn(List.of(region("a", 0, 0)));
            when(f.world.addPluginChunkTicket(anyInt(), anyInt(), eq(f.plugin))).thenThrow(new IllegalStateException("ticket"));
            var result = f.service.reload(); f.next().accept(mock(ScheduledTask.class));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
        }
        var desired = KeepLoadedService.desired(List.of(region("edge", Integer.MAX_VALUE, Integer.MAX_VALUE), region("negative", -1, -1)));
        assertEquals(2, desired.get("atlas:arena").size());
        for (int x : new int[] {-1, Integer.MAX_VALUE})
            assertEquals(x, KeepLoadedService.chunkX(KeepLoadedService.chunkKey(x, -1)));
    }
}
