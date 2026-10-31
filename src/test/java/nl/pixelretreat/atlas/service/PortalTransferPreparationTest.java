package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import dev.veyra.api.*;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.SpawnPoint;
import nl.pixelretreat.controller.api.*;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;

class PortalTransferPreparationTest {
    final Plugin plugin=mock(Plugin.class,RETURNS_DEEP_STUBS);
    final Player player=mock(Player.class,RETURNS_DEEP_STUBS);
    final World world=mock(World.class);
    final UUID playerId=UUID.randomUUID();
    final TransferPlan plan=new TransferPlan(UUID.randomUUID(),"atlas",playerId,new PlayerConnection.Session(UUID.randomUUID(),UUID.randomUUID()),
        "Hub01","FixtureHub",UUID.randomUUID(),"Survival01","FixtureSurvival");
    final TravelTicket ticket=new TravelTicket(plan.id(),playerId,"original","atlas:original",Optional.of(new SpawnPoint(4,70,8,0,0)));
    final AtlasRepository repository=mock(AtlasRepository.class);
    final BoundConnectionsApi bindings=mock(BoundConnectionsApi.class);
    final TeleportService teleports=mock(TeleportService.class);
    final CompletableFuture<TeleportService.Outcome> movement=new CompletableFuture<>();
    final PlayerCheckpoints checkpoints=mock(PlayerCheckpoints.class);
    final AtlasWorkers workers=new AtlasWorkers(2,16);
    PortalService service;
    @BeforeEach void setup()throws Exception {
        when(plugin.isEnabled()).thenReturn(true);when(player.isOnline()).thenReturn(true);when(player.getUniqueId()).thenReturn(playerId);
        when(player.hasPermission("atlas.portal.use")).thenReturn(true);when(plugin.getServer().getPlayer(playerId)).thenReturn(player);
        when(plugin.getServer().getWorld(any(NamespacedKey.class))).thenReturn(world);
        when(player.getScheduler().run(eq(plugin),any(),any())).thenAnswer(a->{a.<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>>getArgument(1).accept(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);});
        var connection=new PlayerConnection(new KnownPlayer(playerId,"Fixture"),PlayerConnection.Status.ONLINE,Optional.of(plan.session()),
            Optional.of(new PlayerConnection.Backend(plan.targetBackend(),UUID.randomUUID())),Instant.now(),Optional.empty());
        when(bindings.capture(player)).thenReturn(CompletableFuture.completedStage(Optional.of(new BoundConnection(UUID.randomUUID(),UUID.randomUUID(),connection,Instant.now()))));
        when(repository.claimTransferArrival(plan)).thenReturn(Optional.of(new AtlasRepository.TransferArrival(ticket,"ARRIVING",true)));
        when(repository.finishTransferArrival(eq(plan),anyBoolean())).thenReturn(true);
        when(teleports.teleportDetailed(eq(player),eq(world),eq(ticket.point()),any())).thenReturn(movement);
        service=new PortalService(plugin,repository,workers,null,teleports,mock(AtlasMessages.class));service.transfers(mock(InventoryTransferApi.class),bindings,checkpoints);
    }
    @AfterEach void close(){service.close();workers.close();}
    @Test void aStaleUnboundLegacyTicketCannotAddASecondJoinTeleport()throws Exception {
        when(repository.takeTicket(playerId)).thenReturn(Optional.of(ticket));
        assertTrue(service.arrive(player).get(5,TimeUnit.SECONDS).isEmpty());
        verify(repository,never()).takeTicket(any());verifyNoInteractions(teleports);
    }
    @Test void originalPortalCannotBecomeDurablyReadyBeforeItsDestinationSave()throws Exception {
        var saved=new CompletableFuture<CheckpointResult>();when(checkpoints.checkpoint(player)).thenReturn(saved);
        try(var nativeApi=mockStatic(Veyra.class)) {
            nativeApi.when(Veyra::playerCheckpoints).thenReturn(checkpoints);
            var ready=service.prepareArrival(plan,player).toCompletableFuture();
            verify(teleports,timeout(5000)).teleportDetailed(eq(player),eq(world),eq(ticket.point()),any());
            movement.complete(TeleportService.Outcome.ARRIVED);assertFalse(ready.isDone());verify(repository,never()).finishTransferArrival(any(),anyBoolean());
            saved.complete(new CheckpointResult(CheckpointResult.Status.SAVED,CheckpointResult.Reason.NONE));
            assertEquals(InventoryTransferApi.Preparation.READY,ready.get(5,TimeUnit.SECONDS));verify(repository).finishTransferArrival(plan,true);
        }
    }
    @Test void restartedUnknownDispatchNeverAuthorizesAnotherTeleport()throws Exception {
        when(repository.claimTransferArrival(plan)).thenReturn(Optional.of(new AtlasRepository.TransferArrival(ticket,"ARRIVING",false)));
        assertEquals(InventoryTransferApi.Preparation.UNKNOWN,service.prepareArrival(plan,player).toCompletableFuture().get(5,TimeUnit.SECONDS));
        verifyNoInteractions(teleports);verify(repository,never()).finishTransferArrival(any(),anyBoolean());
    }
    @Test void unknownSaveRetainsTheOriginalUnfinishedArrivalWithoutPersistingSuccess()throws Exception {
        when(checkpoints.checkpoint(player)).thenReturn(CompletableFuture.completedStage(new CheckpointResult(CheckpointResult.Status.UNKNOWN,CheckpointResult.Reason.WRITE_FAILED)));
        try(var nativeApi=mockStatic(Veyra.class)) {
            nativeApi.when(Veyra::playerCheckpoints).thenReturn(checkpoints);
            var ready=service.prepareArrival(plan,player).toCompletableFuture();
            verify(teleports,timeout(5000)).teleportDetailed(eq(player),eq(world),eq(ticket.point()),any());movement.complete(TeleportService.Outcome.ARRIVED);
            assertEquals(InventoryTransferApi.Preparation.UNKNOWN,ready.get(5,TimeUnit.SECONDS));verify(repository,never()).finishTransferArrival(any(),anyBoolean());
        }
    }
    @Test void provenCancelledOriginalMovementIsReadyWithoutInventingAnArrival()throws Exception {
        var ready=service.prepareArrival(plan,player).toCompletableFuture();
        verify(teleports,timeout(5000)).teleportDetailed(eq(player),eq(world),eq(ticket.point()),any());movement.complete(TeleportService.Outcome.NOT_PERFORMED);
        assertEquals(InventoryTransferApi.Preparation.READY,ready.get(5,TimeUnit.SECONDS));verify(repository).finishTransferArrival(plan,false);
    }
}
