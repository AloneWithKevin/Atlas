package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import nl.pixelretreat.closet.api.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;

class SelectorDeliveryServiceTest {
    final Plugin plugin = mock(Plugin.class);
    final ClosetService closet = mock(ClosetService.class);
    final ClosetLiveItems items = mock(ClosetLiveItems.class);
    final Player target = mock(Player.class);
    final CommandSender console = mock(CommandSender.class);
    final PreparedItems prepared = mock(PreparedItems.class);
    final ItemStack actual = mock(ItemStack.class);
    final CompletableFuture<PreparedItems> preparation = new CompletableFuture<>();
    boolean current = true, menu;
    int schedules;
    SelectorDeliveryService service;
    @BeforeEach void setup() {
        when(plugin.isEnabled()).thenReturn(true);
        when(console.isOp()).thenReturn(true);
        when(actual.getAmount()).thenReturn(1);
        when(closet.prepare(any(), any())).thenReturn(preparation);
        when(closet.materialize(prepared, target)).thenReturn(List.of(actual));
        when(items.deliverWithOverflowTraced(eq(plugin), any(), eq(target), eq(List.of(actual)))).thenAnswer(call ->
            CompletableFuture.completedFuture(new TracedLiveDeliveryResult(new LiveDeliveryResult(call.getArgument(1),
                LiveDeliveryResult.Status.APPLIED, "APPLIED", 1, List.of()), List.of())));
        service = new SelectorDeliveryService(plugin, closet, items, new SelectorDeliveryService.Owners() {
            public boolean current(Player original) { return current && original == target; }
            public boolean owned(Player original) { return true; }
            public boolean schedule(Player original, Runnable run, Runnable retired) { schedules++; run.run(); return true; }
            public boolean menu(Player original) { return menu; }
        });
    }
    @Test void directOriginalOwnerIssuesOneConcreteSelectorWithoutSqlOrExtraTickAndDoesNotRequireRecipientAdmin() throws Exception {
        preparation.complete(prepared);
        assertEquals(SelectorDeliveryService.Result.GIVEN, service.request(target, console).get());
        verify(closet).materialize(prepared, target);
        verify(items).deliverWithOverflowTraced(eq(plugin), any(), same(target), eq(List.of(actual)));
        verify(target, never()).hasPermission(anyString()); assertEquals(0, schedules);
    }
    @Test void delayedPrepareCannotIssueIntoANewCompassMenu() throws Exception {
        var pending = service.request(target, console); menu = true; preparation.complete(prepared);
        assertEquals(SelectorDeliveryService.Result.PENDING, pending.get());
        verify(closet, never()).materialize(any(), any(Player.class)); verifyNoInteractions(items);
    }
    @Test void delayedPrepareCannotRetargetANewConnectionOrIgnoreRequesterPermissionLoss() throws Exception {
        var pending = service.request(target, console); current = false; preparation.complete(prepared);
        assertEquals(SelectorDeliveryService.Result.PENDING, pending.get()); verifyNoInteractions(items);
        current = true; when(console.isOp()).thenReturn(false);
        assertEquals(SelectorDeliveryService.Result.PENDING, service.request(target, console).get());
        verify(closet, never()).materialize(any(), any(Player.class));
    }
    @Test void unknownOrMismatchedDeliveryCannotClaimSuccessOrRetry() throws Exception {
        preparation.complete(prepared);
        for (var status : List.of(LiveDeliveryResult.Status.UNCERTAIN, LiveDeliveryResult.Status.APPLIED)) {
            when(items.deliverWithOverflowTraced(any(), any(), any(), anyList())).thenReturn(CompletableFuture.completedFuture(
                new TracedLiveDeliveryResult(new LiveDeliveryResult(UUID.randomUUID(), status, "UNKNOWN", 1, List.of()), List.of())));
            assertEquals(SelectorDeliveryService.Result.PENDING, service.request(target, console).get());
        }
        verify(items, times(2)).deliverWithOverflowTraced(any(), any(), same(target), anyList());
    }
    @Test void independentCommandsAreNotMergedByAGenericPlayerBusyGuard() throws Exception {
        var first = service.request(target, console); var second = service.request(target, console);
        preparation.complete(prepared);
        assertEquals(SelectorDeliveryService.Result.GIVEN, first.get()); assertEquals(SelectorDeliveryService.Result.GIVEN, second.get());
        var ids = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(items, times(2)).deliverWithOverflowTraced(eq(plugin), ids.capture(), same(target), anyList());
        assertNotEquals(ids.getAllValues().get(0), ids.getAllValues().get(1));
    }
    @Test void knownActualMarketOverflowIsReportedOnlyAfterExactCompleteDelivery() throws Exception {
        preparation.complete(prepared);
        when(items.deliverWithOverflowTraced(any(), any(), any(), anyList())).thenAnswer(call ->
            CompletableFuture.completedFuture(new TracedLiveDeliveryResult(new LiveDeliveryResult(call.getArgument(1),
                LiveDeliveryResult.Status.APPLIED, "APPLIED", 1, List.of()),
                List.of(new LiveOverflowDelivery(UUID.randomUUID(), UUID.randomUUID(), 1)))));
        assertEquals(SelectorDeliveryService.Result.OVERFLOW, service.request(target, console).get());
    }
    @Test void partialOrUnknownOriginalCorrelationNeverAuthorizesSuccessOrPhysicalReplay() throws Exception {
        preparation.complete(prepared);
        for (var status : List.of(LiveDeliveryResult.Status.APPLIED, LiveDeliveryResult.Status.UNCERTAIN)) {
            doAnswer(call -> CompletableFuture.completedFuture(new TracedLiveDeliveryResult(
                new LiveDeliveryResult(call.getArgument(1), status, "INCOMPLETE", 0, List.of()), List.of())))
                .when(items).deliverWithOverflowTraced(any(), any(), any(), anyList());
            assertEquals(SelectorDeliveryService.Result.PENDING, service.request(target, console).get());
        }
        verify(items, times(2)).deliverWithOverflowTraced(any(), any(), same(target), anyList());
    }
    @Test void foreignOwnerCannotMaterializeUntilTheOriginalTargetSchedulerRuns() throws Exception {
        var queue = new java.util.ArrayDeque<Runnable>();
        var foreign = new SelectorDeliveryService(plugin, closet, items, new SelectorDeliveryService.Owners() {
            public boolean current(Player original) { return original == target; }
            public boolean owned(Player original) { return false; }
            public boolean schedule(Player original, Runnable work, Runnable retired) { assertSame(target, original); queue.add(work); return true; }
            public boolean menu(Player original) { return false; }
        });
        var request = foreign.request(target, console);
        verifyNoInteractions(closet, items);
        queue.remove().run(); preparation.complete(prepared);
        verify(closet, never()).materialize(any(), any(Player.class));
        assertFalse(request.isDone()); queue.remove().run();
        assertEquals(SelectorDeliveryService.Result.GIVEN, request.get());
        verify(closet).materialize(prepared, target);
    }

}
