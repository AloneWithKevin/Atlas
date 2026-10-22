package nl.pixelretreat.atlas.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import nl.pixelretreat.atlas.listener.SelectorListener;
import nl.pixelretreat.closet.api.*;
import nl.pixelretreat.compass.api.CompassApi;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** One current command invocation, with no physical intent, deduplication, saved proof or reconnect replay. */
public final class SelectorDeliveryService {
    public enum Result { GIVEN, OVERFLOW, PENDING, NONE }
    interface Owners {
        boolean current(Player original);
        boolean owned(Player original);
        boolean schedule(Player original, Runnable work, Runnable retired);
        boolean menu(Player original);
    }
    private final Plugin plugin;
    private final ClosetService closet;
    private final ClosetLiveItems items;
    private final Owners owners;

    public SelectorDeliveryService(Plugin plugin, ClosetService closet, ClosetLiveItems items, CompassApi compass) {
        this(plugin, closet, items, new Owners() {
            public boolean current(Player player) { return player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player; }
            public boolean owned(Player player) { return Bukkit.isOwnedByCurrentRegion(player); }
            public boolean schedule(Player player, Runnable work, Runnable retired) { return player.getScheduler().execute(plugin, work, retired, 0); }
            public boolean menu(Player player) { return compass.openMenu(player).isPresent(); }
        });
    }
    SelectorDeliveryService(Plugin plugin, ClosetService closet, ClosetLiveItems items, Owners owners) {
        this.plugin = plugin; this.closet = closet; this.items = items; this.owners = owners;
    }

    /** Capture the actual target and requester; a later connection can never receive this invocation. */
    public CompletableFuture<Result> request(Player original, CommandSender requester) {
        UUID correlation = UUID.randomUUID();
        return permitted(requester).thenCompose(allowed -> !allowed ? done(Result.PENDING)
            : onOwner(original, () -> available(original)).thenCompose(ready -> !ready ? done(Result.PENDING)
                : closet.prepare(ItemRequest.of(SelectorListener.SELECTOR, 1), RenderContext.english())
                    .thenCompose(prepared -> permitted(requester).thenCompose(stillAllowed -> !stillAllowed ? done(Result.PENDING)
                        : onOwner(original, () -> {
                            if (!available(original)) return done(Result.PENDING);
                            List<ItemStack> actual = closet.materialize(prepared, original);
                            if (actual.stream().mapToLong(ItemStack::getAmount).sum() != 1) return done(Result.PENDING);
                            return items.deliverWithOverflowTraced(plugin, correlation, original, actual).thenApply(trace -> {
                                LiveDeliveryResult result = trace.delivery();
                                if (!correlation.equals(result.requestId()) || !result.applied() || result.appliedUnits() != 1)
                                    return Result.PENDING;
                                return trace.overflow().isEmpty() ? Result.GIVEN : Result.OVERFLOW;
                            }).toCompletableFuture();
                        }).thenCompose(value -> value))))).exceptionally(failure -> Result.PENDING);
    }
    private boolean available(Player original) { return plugin.isEnabled() && owners.current(original) && !owners.menu(original); }
    private CompletableFuture<Boolean> permitted(CommandSender actor) {
        if (actor instanceof Player original) return onOwner(original,
            () -> plugin.isEnabled() && owners.current(original) && (original.isOp() || original.hasPermission(SelectorListener.ADMIN)));
        return done(plugin.isEnabled() && (actor.isOp() || actor.hasPermission(SelectorListener.ADMIN)));
    }
    private <T> CompletableFuture<T> onOwner(Player original, Supplier<T> work) {
        var result = new CompletableFuture<T>();
        Runnable run = () -> { try { result.complete(work.get()); } catch (Throwable failure) { result.completeExceptionally(failure); } };
        if (owners.owned(original)) run.run();
        else if (!owners.schedule(original, run, () -> result.completeExceptionally(new IllegalStateException("Original player retired"))))
            result.completeExceptionally(new IllegalStateException("Original player unavailable"));
        return result;
    }
    private static <T> CompletableFuture<T> done(T result) { return CompletableFuture.completedFuture(result); }
}
