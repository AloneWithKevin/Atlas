package nl.pixelretreat.atlas.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import nl.pixelretreat.atlas.listener.SelectorListener;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.closet.api.ClosetDeliveries;
import nl.pixelretreat.closet.api.DeliveryReceipt;
import nl.pixelretreat.closet.api.FreshDeliveryRequest;
import nl.pixelretreat.closet.api.ItemRequest;
import org.bukkit.plugin.Plugin;

/** Retains the selector business ID; Closet alone owns frozen goods, placement and overflow. */
public final class SelectorDeliveryService {
    public enum Result { GIVEN, OVERFLOW, PENDING, NONE }
    private final Plugin plugin;
    private final AtlasRepository repository;
    private final AtlasWorkers workers;
    private final ClosetDeliveries deliveries;
    private final ConcurrentHashMap<UUID, CompletableFuture<Result>> active = new ConcurrentHashMap<>();

    public SelectorDeliveryService(Plugin plugin, AtlasRepository repository, AtlasWorkers workers, ClosetDeliveries deliveries) {
        this.plugin = plugin; this.repository = repository; this.workers = workers; this.deliveries = deliveries;
    }

    /** A command creates one intent, or resumes the original still-pending intent. */
    public CompletableFuture<Result> request(UUID player) {
        return start(player, true).thenCompose(value -> value == Result.NONE ? start(player, true)
                : CompletableFuture.completedFuture(value));
    }
    /** Startup/join recovery never creates a request. */
    public CompletableFuture<Result> resume(UUID player) { return start(player, false); }

    private CompletableFuture<Result> start(UUID player, boolean create) {
        var result = new CompletableFuture<Result>();
        var previous = active.putIfAbsent(player, result);
        if (previous != null) return previous;
        workers.submit(() -> create ? Optional.of(repository.reserveSelector(player, UUID.randomUUID()))
                        : repository.pendingSelector(player))
                .thenCompose(id -> id.isEmpty() ? CompletableFuture.completedFuture(Result.NONE) : deliver(player, id.get()))
                .whenComplete((value, failure) -> {
                    active.remove(player, result);
                    // A failed acknowledgement keeps the durable original ID for later reconciliation.
                    result.complete(failure == null ? value : Result.PENDING);
                });
        return result;
    }

    private CompletableFuture<Result> deliver(UUID player, UUID operation) {
        return deliveries.reconcile(plugin, operation).toCompletableFuture().thenCompose(existing -> {
            if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());
            return deliveries.issue(plugin, new FreshDeliveryRequest(operation, player, Optional.of(player),
                    "atlas.selector", List.of(ItemRequest.of(SelectorListener.SELECTOR, 1)))).toCompletableFuture();
        }).thenCompose(receipt -> {
            if (!receipt.operationId().equals(operation) || !receipt.recipientId().equals(player)
                    || receipt.expectedUnits() != 1 || receipt.status() != DeliveryReceipt.Status.COMPLETE)
                return CompletableFuture.completedFuture(Result.PENDING);
            Result delivered = receipt.mailboxUnits() > 0 ? Result.OVERFLOW : Result.GIVEN;
            return workers.submit(() -> {
                repository.completeSelector(player, operation);
                return delivered;
            }).handle((value, failure) -> delivered); // COMPLETE already proves the goods; a failed pointer write only needs recovery.
        });
    }
}
