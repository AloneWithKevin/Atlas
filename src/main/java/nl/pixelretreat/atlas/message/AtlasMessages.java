package nl.pixelretreat.atlas.message;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pixelretreat.campfire.api.CampfireDelivery;
import nl.pixelretreat.campfire.api.DeliveryOptions;
import nl.pixelretreat.campfire.api.DeliveryResult;
import nl.pixelretreat.campfire.api.MessageCatalog;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Atlas' Campfire message contract and delivery. Every key and its placeholders are listed here,
 * so a missing or broken key stops Atlas at startup instead of failing in front of a player.
 */
public final class AtlasMessages {
    private static final Set<String> WORLD = Set.of("world");
    private static final Set<String> PORTAL = Set.of("portal");
    private static final Set<String> CORNER = Set.of("x", "y", "z", "world");

    /** Every message key with its exact placeholder names. */
    public static final Map<String, Set<String>> CONTRACT = Map.ofEntries(
            Map.entry("startup.ready", Set.of()), Map.entry("startup.failed", Set.of()),
            Map.entry("startup.operation-done", Set.of("id", "kind", "world")),
            Map.entry("startup.operation-failed", Set.of("id", "kind", "world", "detail")),
            Map.entry("startup.datapack-changed", Set.of()), Map.entry("delivery.failed", Set.of()),
            Map.entry("common.permission", Set.of()),
            Map.entry("common.players-only", Set.of()), Map.entry("common.unknown-world", WORLD),
            Map.entry("common.not-loaded", WORLD), Map.entry("common.player-offline", Set.of("player")),
            Map.entry("common.failed", Set.of()), Map.entry("common.invalid-name", Set.of()),
            Map.entry("help.lines", Set.of()),
            Map.entry("usage.world", Set.of()), Map.entry("usage.info", Set.of()), Map.entry("usage.tp", Set.of()),
            Map.entry("usage.spawn", Set.of()), Map.entry("usage.setspawn", Set.of()), Map.entry("usage.flag", Set.of()),
            Map.entry("usage.set", Set.of()), Map.entry("usage.portal", Set.of()), Map.entry("usage.keeploaded", Set.of()),
            Map.entry("world.unknown-generator", Set.of()),
            Map.entry("world.created", WORLD), Map.entry("world.imported", WORLD), Map.entry("world.enabled", WORLD),
            Map.entry("world.disabled", WORLD), Map.entry("world.delete-queued", WORLD),
            Map.entry("world.clone-queued", Set.of("world", "source")), Map.entry("world.reset-queued", WORLD),
            Map.entry("world.confirm-delete", WORLD), Map.entry("world.confirm-reset", WORLD),
            Map.entry("world.pending-empty", Set.of()),
            Map.entry("world.pending-entry", Set.of("id", "kind", "world", "source")),
            Map.entry("world.cancelled", Set.of("id")), Map.entry("world.operation-not-found", Set.of("id")),
            Map.entry("world.state-loaded", Set.of("world", "generator")),
            Map.entry("world.state-pending", Set.of("world", "generator")),
            Map.entry("world.state-unloading", Set.of("world", "generator")),
            Map.entry("world.state-disabled", Set.of("world", "generator")),
            Map.entry("world.error.unknown-world", WORLD), Map.entry("world.error.built-in", WORLD),
            Map.entry("world.error.already-exists", WORLD), Map.entry("world.error.folder-exists", WORLD),
            Map.entry("world.error.folder-missing", WORLD), Map.entry("world.error.has-portals", WORLD),
            Map.entry("world.error.has-regions", WORLD), Map.entry("world.error.no-source", WORLD),
            Map.entry("world.error.same-world", WORLD), Map.entry("world.error.already-enabled", WORLD),
            Map.entry("world.error.already-disabled", WORLD), Map.entry("world.error.not-found", WORLD),
            Map.entry("info.header", WORLD),
            Map.entry("info.state", Set.of("loaded", "enabled", "generator", "players")),
            Map.entry("info.settings", Set.of("difficulty", "gamemode", "time", "weather", "spawn", "source")),
            Map.entry("info.flags", Set.of("flags")),
            Map.entry("teleport.failed", WORLD), Map.entry("teleport.arrived", WORLD),
            Map.entry("teleport.sent", Set.of("player", "world")),
            Map.entry("spawn.wrong-world", Set.of()), Map.entry("spawn.set", WORLD),
            Map.entry("flag.unknown", Set.of("flags")), Map.entry("flag.set", Set.of("flag", "world", "value")),
            Map.entry("setting.invalid", Set.of("setting")), Map.entry("setting.set", Set.of("setting", "world", "value")),
            Map.entry("selector.given", Set.of()), Map.entry("selector.pending", Set.of()),
            Map.entry("selector.first", CORNER), Map.entry("selector.second", CORNER),
            Map.entry("selection.missing", Set.of()), Map.entry("selection.different-worlds", Set.of()),
            Map.entry("portal.list-empty", Set.of()), Map.entry("portal.list-entry", Set.of("portal", "world", "target")),
            Map.entry("portal.created", Set.of("portal", "world")), Map.entry("portal.exists", PORTAL),
            Map.entry("portal.deleted", PORTAL), Map.entry("portal.not-found", PORTAL),
            Map.entry("portal.info", Set.of("portal", "world", "bounds", "target", "cooldown", "sound", "particle",
                    "restricted", "fill")),
            Map.entry("portal.cooldown-set", Set.of("portal", "millis")), Map.entry("portal.invalid-sound", Set.of()),
            Map.entry("portal.sound-set", Set.of("portal", "sound")), Map.entry("portal.invalid-particle", Set.of()),
            Map.entry("portal.particle-set", Set.of("portal", "particle")),
            Map.entry("portal.restrict-set", Set.of("portal", "value", "permission")),
            Map.entry("portal.invalid-block", Set.of()), Map.entry("portal.fill-too-large", Set.of("max")),
            Map.entry("portal.fill-set", Set.of("portal", "block")), Map.entry("portal.invalid-region", Set.of()),
            Map.entry("portal.target-set", Set.of("portal", "target")), Map.entry("portal.switching", Set.of("region")),
            Map.entry("portal.target-missing", WORLD), Map.entry("portal.failed", Set.of()),
            Map.entry("portal.arrival-failed", Set.of()),
            Map.entry("keeploaded.list-header", Set.of("count", "tickets")),
            Map.entry("keeploaded.list-entry", Set.of("region", "world", "chunks", "minx", "minz", "maxx", "maxz")),
            Map.entry("keeploaded.too-large", Set.of("chunks", "max")),
            Map.entry("keeploaded.saved", Set.of("region", "world", "chunks")),
            Map.entry("keeploaded.deleted", Set.of("region")), Map.entry("keeploaded.not-found", Set.of("region")),
            Map.entry("reload.done", Set.of()), Map.entry("reload.failed", Set.of()),
            Map.entry("items.selector.name", Set.of()), Map.entry("items.selector.lore", Set.of()));

    private final Plugin owner;
    private final MessageCatalog catalog;
    private final CampfireDelivery delivery;
    private final AtomicBoolean deliveryFailureLogged = new AtomicBoolean();
    private final Map<UUID, CompletableFuture<Void>> playerQueues = new ConcurrentHashMap<>();

    public AtlasMessages(Plugin owner, MessageCatalog catalog, CampfireDelivery delivery) {
        this.owner = owner;
        this.catalog = catalog;
        this.delivery = delivery;
        validateCatalog(catalog);
    }

    /** Renders every key with its placeholders; throws when a key is missing or malformed. */
    public static void validateCatalog(MessageCatalog catalog) {
        CONTRACT.forEach((key, names) -> catalog.get(key,
                names.stream().collect(Collectors.toUnmodifiableMap(name -> name, name -> "check"))));
    }

    /** Sends a keyed message; inserted values are plain text and never parsed. Any thread. */
    public void send(CommandSender sender, String key, Map<String, String> values) {
        if (!owner.isEnabled()) return;
        if (sender instanceof Player player) {
            UUID id = player.getUniqueId();
            CompletableFuture<Void> queued = playerQueues.compute(id, (ignored, previous) -> {
                CompletableFuture<Void> tail = previous == null ? CompletableFuture.completedFuture(null)
                        : previous.handle((done, failure) -> null);
                CompletableFuture<Void> next = tail.thenCompose(done -> delivery
                        .send(owner, player, key, values, Map.of(), DeliveryOptions.serverChat())
                        .thenAccept(result -> {
                            if (result == DeliveryResult.SENT) { deliveryFailureLogged.set(false); return; }
                            if (result == DeliveryResult.RECIPIENT_OFFLINE || result == DeliveryResult.OWNER_DISABLED) return;
                            throw new IllegalStateException(result.name());
                        })).toCompletableFuture();
                next.whenComplete((done, failure) -> {
                    playerQueues.remove(id, next);
                    if (failure != null && deliveryFailureLogged.compareAndSet(false, true)) {
                        owner.getLogger().warning(plain("delivery.failed"));
                    }
                });
                return next;
            });
            if (queued.isDone()) playerQueues.remove(id, queued);
        } else {
            owner.getServer().getGlobalRegionScheduler().run(owner, ignored -> sender.sendMessage(catalog.get(key, values)));
        }
    }

    /** Sends a key without placeholders. */
    public void send(CommandSender sender, String key) { send(sender, key, Map.of()); }

    /** Sends {@code common.failed} when the action failed, otherwise the success key. */
    public void reply(CommandSender sender, Throwable failure, String key, Map<String, String> values) {
        if (failure != null) send(sender, "common.failed");
        else send(sender, key, values);
    }

    /** A message as plain text for the console. */
    public String plain(String key) { return PlainTextComponentSerializer.plainText().serialize(catalog.get(key)); }

    /** A message with placeholders as plain text for the console. */
    public String plain(String key, Map<String, String> values) {
        return PlainTextComponentSerializer.plainText().serialize(catalog.get(key, values));
    }
}
