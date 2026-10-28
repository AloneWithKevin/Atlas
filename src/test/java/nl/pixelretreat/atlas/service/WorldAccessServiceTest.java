package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.veyra.api.WorldLifecycle;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.api.AtlasWorldAccess.Result;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import nl.pixelretreat.atlas.world.WorldGenerator;
import nl.pixelretreat.atlas.world.WorldRecord;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldAccessServiceTest {
    Plugin plugin = mock(Plugin.class);
    Server server = mock(Server.class);
    AtlasRepository repository = mock(AtlasRepository.class);
    RulesService rules = mock(RulesService.class);
    TeleportService teleports = mock(TeleportService.class);
    WorldLifecycle nativeWorlds = mock(WorldLifecycle.class);
    AtlasMessages messages = mock(AtlasMessages.class);
    AtomicReference<RulesSnapshot> state = new AtomicReference<>();
    World source = mock(World.class), spawn = mock(World.class);
    UUID spawnId = UUID.randomUUID();
    WorldAccessService access;
    @BeforeEach void setup() throws Exception {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        var global = mock(GlobalRegionScheduler.class);
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        doAnswer(call -> { call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class); })
                .when(global).run(eq(plugin), any());
        when(source.getKey()).thenReturn(NamespacedKey.fromString("atlas:comet"));
        when(spawn.getKey()).thenReturn(NamespacedKey.fromString("minecraft:overworld"));
        when(server.getWorld(spawnId)).thenReturn(spawn);
        doReturn(List.of()).when(server).getOnlinePlayers();
        state.set(snapshot(false));
        when(rules.snapshot()).thenAnswer(call -> state.get());
        when(rules.serialized(any())).thenAnswer(call -> CompletableFuture.completedFuture(call.<java.util.concurrent.Callable<?>>getArgument(0).call()));
        doAnswer(call -> { state.set(snapshot(call.getArgument(1))); return null; }).when(repository).setClosed(eq("atlas:comet"), anyBoolean());
        when(repository.loadWorlds()).thenReturn(Map.of());
        when(nativeWorlds.closed(eq(plugin), any(), anyBoolean(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(nativeWorlds.spawn(plugin)).thenReturn(CompletableFuture.completedFuture(Optional.of(destination())));
        access = new WorldAccessService(plugin, repository, rules, teleports, nativeWorlds, messages);
    }
    private WorldLifecycle.Destination destination() { return new WorldLifecycle.Destination(spawnId, 12, 70, 34, 90, 0); }
    private RulesSnapshot snapshot(boolean closed) {
        var flags = new java.util.EnumMap<AtlasFlag, Boolean>(AtlasFlag.class);
        for (var flag : AtlasFlag.values()) flags.put(flag, true);
        var record = new WorldRecord("atlas:comet", Optional.of(WorldGenerator.NORMAL), true, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), closed);
        return new RulesSnapshot(Map.of(record.key(), record), flags);
    }
    private Player player(boolean staff) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(source);
        when(player.hasPermission(WorldAccessService.BYPASS)).thenReturn(staff);
        var scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(call -> {
            call.<Consumer<ScheduledTask>>getArgument(1).accept(mock(ScheduledTask.class)); return mock(ScheduledTask.class);
        });
        return player;
    }
    @Test void refusesUnknownSpawnBeforeChangingAccess() throws Exception {
        when(nativeWorlds.spawn(plugin)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        assertEquals(Result.SPAWN_UNAVAILABLE, access.setClosed("atlas:comet", true).join());
        verify(repository, never()).setClosed(anyString(), anyBoolean());
        verify(nativeWorlds, never()).closed(any(), any(), anyBoolean(), any(), any());
    }
    @Test void refusesSpawnInsideTheWorldBeingClosed() throws Exception {
        when(server.getWorld(spawnId)).thenReturn(source);
        assertEquals(Result.SPAWN_UNAVAILABLE, access.setClosed("atlas:comet", true).join());
        verify(repository, never()).setClosed(anyString(), anyBoolean());
    }
    @Test void openDoesNotNeedSpawnAndNeverMovesStaffOrPlayers() {
        assertEquals(Result.DONE, access.setClosed("atlas:comet", false).join());
        verify(nativeWorlds, never()).spawn(any());
        verifyNoInteractions(teleports);
    }
    @Test void staffRemainInsideClosedWorld() {
        doReturn(List.of(player(true))).when(server).getOnlinePlayers();
        assertEquals(Result.DONE, access.setClosed("atlas:comet", true).join());
        verifyNoInteractions(teleports);
    }
    @Test void oldAcceptedArrivalIsEvacuatedBeforeCloseReportsSuccess() {
        CompletableFuture<Void> oldMove = new CompletableFuture<>();
        when(nativeWorlds.closed(eq(plugin), any(), eq(true), any(), any())).thenReturn(oldMove);
        var close = access.setClosed("atlas:comet", true);
        assertFalse(close.isDone());
        assertEquals(Result.BUSY, access.setClosed("atlas:comet", false).join());
        Player late = player(false);
        doReturn(List.of(late)).when(server).getOnlinePlayers();
        when(teleports.teleportDetailed(eq(late), eq(spawn), any(), any())).thenAnswer(call -> {
            assertTrue(call.<java.util.function.BooleanSupplier>getArgument(3).getAsBoolean());
            when(late.getWorld()).thenReturn(spawn);
            return CompletableFuture.completedFuture(TeleportService.Outcome.ARRIVED);
        });
        oldMove.complete(null);
        assertEquals(Result.DONE, close.join());
        verify(teleports).teleportDetailed(eq(late), eq(spawn), argThat(point -> point.isPresent()
                && point.get().x() == 12 && point.get().z() == 34), any());
    }
    @Test void uncertainEvacuationRetainsClosedStateWithoutSuccess() {
        Player normal = player(false);
        doReturn(List.of(normal)).when(server).getOnlinePlayers();
        when(teleports.teleportDetailed(eq(normal), eq(spawn), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(TeleportService.Outcome.UNKNOWN));
        assertEquals(Result.EVICTION_INCOMPLETE, access.setClosed("atlas:comet", true).join());
        assertTrue(access.closed("atlas:comet"));
    }
    @Test void nativeEnablePublicationAndCloseShareTheSameOriginalKeyGate() {
        assertTrue(access.beginLifecycle("atlas:comet"));
        assertEquals(Result.BUSY, access.setClosed("atlas:comet", true).join());
        verifyNoInteractions(nativeWorlds);
        access.endLifecycle("atlas:comet");
        assertEquals(Result.DONE, access.setClosed("atlas:comet", true).join());
    }
    @Test void brokenRefusalRenderingCannotCommitSourceOrPublishNativeAccess() throws Exception {
        when(messages.render(anyString(), anyMap())).thenThrow(new IllegalStateException("catalog unavailable"));
        assertEquals(Result.FAILED, access.setClosed("atlas:comet", true).join());
        verify(repository, never()).setClosed(anyString(), anyBoolean());
        verifyNoInteractions(nativeWorlds);
        assertFalse(access.closed("atlas:comet"));
    }
    @Test void cancelledCloseResponseCannotSkipOrPrematurelyReleaseOriginalGate() {
        CompletableFuture<Void> oldMove = new CompletableFuture<>();
        when(nativeWorlds.closed(eq(plugin), any(), eq(true), any(), any())).thenReturn(oldMove);
        var response = access.setClosed("atlas:comet", true);
        assertTrue(response.cancel(false));
        assertEquals(Result.BUSY, access.setClosed("atlas:comet", false).join());
        oldMove.complete(null);
        assertEquals(Result.DONE, access.setClosed("atlas:comet", false).join());
    }
    @Test void cancelledEnableResponseKeepsCloseBlockedOnlyUntilOriginalPublicationFinishes() {
        CompletableFuture<WorldLifecycle.Result> publication = new CompletableFuture<>();
        when(nativeWorlds.enable(eq(plugin), any(), anyString(), any())).thenReturn(publication);
        var admin = new WorldAdminService(repository, null, rules,
                mock(nl.pixelretreat.atlas.world.DatapackWriter.class), null, null, null);
        admin.runtime(plugin, nativeWorlds, access);
        var response = admin.setEnabled("atlas:comet", true);
        assertTrue(response.cancel(false));
        assertEquals(Result.BUSY, access.setClosed("atlas:comet", true).join());
        publication.complete(WorldLifecycle.Result.DONE);
        assertEquals(Result.DONE, access.setClosed("atlas:comet", false).join());
    }
}
