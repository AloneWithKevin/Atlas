package nl.pixelretreat.atlas.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.keeploaded.KeepLoadedRegion;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.service.KeepLoadedService;
import nl.pixelretreat.atlas.service.PortalService;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.service.SelectionService;
import nl.pixelretreat.atlas.service.TeleportService;
import nl.pixelretreat.atlas.service.WorldAdminService;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import nl.pixelretreat.atlas.world.SpawnPoint;
import nl.pixelretreat.atlas.world.WeatherMode;
import nl.pixelretreat.atlas.world.WorldGenerator;
import nl.pixelretreat.atlas.world.WorldNames;
import nl.pixelretreat.atlas.world.WorldRecord;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /atlas}: every staff action. The command only parses input and checks permission; the
 * services do the work asynchronously and the result is reported with a message key.
 */
public final class AtlasCommand implements TabExecutor {
    public static final String ADMIN = "atlas.admin";
    private static final List<String> ROOT = List.of("help", "world", "info", "tp", "spawn", "setspawn", "flag",
            "set", "selector", "portal", "keeploaded", "reload");
    private static final List<String> WORLD = List.of("list", "create", "import", "enable", "disable", "delete",
            "clone", "reset", "pending", "cancel");
    private static final List<String> PORTAL = List.of("create", "delete", "list", "info", "target", "cooldown",
            "sound", "particle", "restrict", "fill");
    private static final List<String> KEEP_LOADED = List.of("set", "delete", "list");
    private static final List<String> SETTINGS = List.of("difficulty", "gamemode", "time", "weather");
    private static final String NAME = "[a-z0-9_-]{1,48}";

    /** Collaborators of the command; it is registered only once all of them are ready. */
    public record Services(AtlasConfig config, RulesService rules, WorldAdminService worlds,
                           TeleportService teleports, PortalService portals, KeepLoadedService keepLoaded,
                           SelectionService selections, Function<Player, CompletableFuture<Boolean>> giveSelector,
                           Supplier<CompletableFuture<Void>> reload) { }

    private final Plugin plugin;
    private final AtlasMessages messages;
    private final Services services;

    public AtlasCommand(Plugin plugin, AtlasMessages messages, Services services) {
        this.plugin = plugin;
        this.messages = messages;
        this.services = services;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) { messages.send(sender, "common.permission"); return true; }
        Services ready = services;
        if (args.length == 0) { messages.send(sender, "help.lines"); return true; }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> messages.send(sender, "help.lines");
            case "world" -> world(sender, ready, rest);
            case "info" -> info(sender, ready, rest);
            case "tp" -> teleport(sender, ready, rest, false);
            case "spawn" -> teleport(sender, ready, rest, true);
            case "setspawn" -> setSpawn(sender, ready, rest);
            case "flag" -> flag(sender, ready, rest);
            case "set" -> setting(sender, ready, rest);
            case "selector" -> selector(sender, ready);
            case "portal" -> portal(sender, ready, rest);
            case "keeploaded" -> keepLoaded(sender, ready, rest);
            case "reload" -> ready.reload().get().whenComplete((done, failure) ->
                    messages.send(sender, failure == null ? "reload.done" : "reload.failed"));
            default -> messages.send(sender, "help.lines");
        }
        return true;
    }

    // ---- world lifecycle ----------------------------------------------------------------------

    private void world(CommandSender sender, Services s, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> listWorlds(sender, s);
            case "create", "import" -> {
                if (args.length != 3) { messages.send(sender, "usage.world"); return; }
                Optional<String> key = newWorld(sender, args[1]);
                Optional<WorldGenerator> generator = WorldGenerator.fromKey(args[2]);
                if (key.isEmpty()) return;
                if (generator.isEmpty()) { messages.send(sender, "world.unknown-generator"); return; }
                var future = action.equals("create") ? s.worlds().create(key.get(), generator.get())
                        : s.worlds().importWorld(key.get(), generator.get());
                report(sender, future, action.equals("create") ? "world.created" : "world.imported", key.get(), null);
            }
            case "enable", "disable" -> {
                if (args.length != 2) { messages.send(sender, "usage.world"); return; }
                worldKey(sender, args[1]).ifPresent(key -> report(sender, s.worlds().setEnabled(key, action.equals("enable")),
                        action.equals("enable") ? "world.enabled" : "world.disabled", key, null));
            }
            case "delete" -> {
                if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                    if (args.length >= 2) messages.send(sender, "world.confirm-delete", Map.of("world", args[1]));
                    else messages.send(sender, "usage.world");
                    return;
                }
                worldKey(sender, args[1]).ifPresent(key ->
                        report(sender, s.worlds().delete(key, actor(sender)), "world.delete-queued", key, null));
            }
            case "clone" -> {
                if (args.length != 3) { messages.send(sender, "usage.world"); return; }
                Optional<String> source = worldKey(sender, args[1]);
                Optional<String> target = source.isEmpty() ? Optional.empty() : newWorld(sender, args[2]);
                if (source.isEmpty() || target.isEmpty()) return;
                report(sender, s.worlds().cloneWorld(source.get(), target.get(), actor(sender)),
                        "world.clone-queued", target.get(), source.get());
            }
            case "reset" -> {
                if (args.length < 3 || !args[args.length - 1].equalsIgnoreCase("confirm") || args.length > 4) {
                    if (args.length >= 2) messages.send(sender, "world.confirm-reset", Map.of("world", args[1]));
                    else messages.send(sender, "usage.world");
                    return;
                }
                Optional<String> key = worldKey(sender, args[1]);
                if (key.isEmpty()) return;
                Optional<String> source = Optional.empty();
                if (args.length == 4) {
                    source = worldKey(sender, args[2]);
                    if (source.isEmpty()) return;
                }
                report(sender, s.worlds().reset(key.get(), source, actor(sender)), "world.reset-queued", key.get(), null);
            }
            case "pending" -> s.worlds().pending().whenComplete((operations, failure) -> {
                if (failure != null) { messages.send(sender, "common.failed"); return; }
                if (operations.isEmpty()) { messages.send(sender, "world.pending-empty"); return; }
                for (var operation : operations) {
                    messages.send(sender, "world.pending-entry", Map.of("id", Long.toString(operation.id()),
                            "kind", operation.kind().name().toLowerCase(Locale.ROOT),
                            "world", WorldNames.shortName(operation.world()),
                            "source", operation.source().map(WorldNames::shortName).orElse("-")));
                }
            });
            case "cancel" -> {
                if (args.length != 2 || !args[1].matches("\\d{1,18}")) { messages.send(sender, "usage.world"); return; }
                s.worlds().cancel(Long.parseLong(args[1])).whenComplete((result, failure) -> {
                    if (failure != null) messages.send(sender, "common.failed");
                    else if (result == WorldAdminService.Result.DONE) messages.send(sender, "world.cancelled", Map.of("id", args[1]));
                    else messages.send(sender, "world.operation-not-found", Map.of("id", args[1]));
                });
            }
            default -> messages.send(sender, "usage.world");
        }
    }

    private void listWorlds(CommandSender sender, Services s) {
        RulesSnapshot snapshot = s.rules().snapshot();
        List<String> keys = new ArrayList<>(List.of(WorldNames.OVERWORLD, WorldNames.NETHER, WorldNames.END));
        snapshot.worlds().stream().map(WorldRecord::key).filter(key -> !WorldNames.builtIn(key)).sorted().forEach(keys::add);
        for (String key : keys) {
            WorldRecord record = snapshot.worldOrBuiltIn(key);
            boolean loaded = loaded(key) != null;
            String state = record.enabled() ? (loaded ? "world.state-loaded" : "world.state-pending")
                    : (loaded ? "world.state-unloading" : "world.state-disabled");
            messages.send(sender, state, Map.of("world", WorldNames.shortName(key),
                    "generator", record.generator().map(WorldGenerator::key).orElse("vanilla")));
        }
    }

    private void report(CommandSender sender, CompletableFuture<WorldAdminService.Result> future, String successKey,
                        String world, String source) {
        future.whenComplete((result, failure) -> {
            if (failure != null) { messages.send(sender, "common.failed"); return; }
            Map<String, String> values = source == null ? Map.of("world", WorldNames.shortName(world))
                    : Map.of("world", WorldNames.shortName(world), "source", WorldNames.shortName(source));
            if (result == WorldAdminService.Result.DONE) { messages.send(sender, successKey, values); return; }
            messages.send(sender, "world.error." + result.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    Map.of("world", WorldNames.shortName(world)));
        });
    }

    // ---- information and teleports -----------------------------------------------------------

    private void info(CommandSender sender, Services s, String[] args) {
        if (args.length != 1) { messages.send(sender, "usage.info"); return; }
        worldKey(sender, args[0]).ifPresent(key -> {
            RulesSnapshot snapshot = s.rules().snapshot();
            WorldRecord record = snapshot.worldOrBuiltIn(key);
            World world = loaded(key);
            messages.send(sender, "info.header", Map.of("world", WorldNames.shortName(key)));
            messages.send(sender, "info.state", Map.of("loaded", Boolean.toString(world != null),
                    "enabled", Boolean.toString(record.enabled()),
                    "generator", record.generator().map(WorldGenerator::key).orElse("vanilla"),
                    "players", world == null ? "0" : Integer.toString(world.getPlayerCount())));
            messages.send(sender, "info.settings", Map.of(
                    "difficulty", record.difficulty().map(Enum::name).orElse("-"),
                    "gamemode", record.gameMode().map(Enum::name).orElse("-"),
                    "time", record.fixedTime().map(String::valueOf).orElse("-"),
                    "weather", record.weather().map(Enum::name).orElse("-"),
                    "spawn", record.spawn().map(p -> (int) p.x() + ", " + (int) p.y() + ", " + (int) p.z()).orElse("-"),
                    "source", record.resetSource().map(WorldNames::shortName).orElse("-")));
            StringJoiner flags = new StringJoiner(", ");
            for (AtlasFlag flag : AtlasFlag.values()) {
                flags.add(flag.key() + "=" + (snapshot.flag(key, flag) ? "on" : "off")
                        + (record.flags().containsKey(flag) ? "*" : ""));
            }
            messages.send(sender, "info.flags", Map.of("flags", flags.toString()));
        });
    }

    private void teleport(CommandSender sender, Services s, String[] args, boolean spawnCommand) {
        if (args.length > 2 || !spawnCommand && args.length == 0) {
            messages.send(sender, spawnCommand ? "usage.spawn" : "usage.tp");
            return;
        }
        Player target;
        if (args.length == 2) {
            target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) { messages.send(sender, "common.player-offline", Map.of("player", args[1])); return; }
        } else if (sender instanceof Player player) target = player;
        else { messages.send(sender, "common.players-only"); return; }
        Optional<String> key = args.length >= 1 ? worldKey(sender, args[0]) : Optional.of(RulesService.key(target.getWorld()));
        if (key.isEmpty()) return;
        World world = loaded(key.get());
        if (world == null) { messages.send(sender, "common.not-loaded", Map.of("world", WorldNames.shortName(key.get()))); return; }
        String name = WorldNames.shortName(key.get());
        s.teleports().teleport(target, world, Optional.empty()).whenComplete((arrived, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(arrived)) {
                messages.send(sender, "teleport.failed", Map.of("world", name));
                return;
            }
            messages.send(target, "teleport.arrived", Map.of("world", name));
            if (target != sender) messages.send(sender, "teleport.sent", Map.of("player", target.getName(), "world", name));
        });
    }

    private void setSpawn(CommandSender sender, Services s, String[] args) {
        if (!(sender instanceof Player player)) { messages.send(sender, "common.players-only"); return; }
        if (args.length > 1) { messages.send(sender, "usage.setspawn"); return; }
        String here = RulesService.key(player.getWorld());
        Optional<String> key = args.length == 1 ? worldKey(sender, args[0]) : Optional.of(here);
        if (key.isEmpty()) return;
        if (!key.get().equals(here)) { messages.send(sender, "spawn.wrong-world"); return; }
        Location at = player.getLocation();
        s.rules().setSpawn(here, new SpawnPoint(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()))
                .whenComplete((done, failure) -> messages.reply(sender, failure, "spawn.set",
                        Map.of("world", WorldNames.shortName(here))));
    }

    // ---- flags and settings -------------------------------------------------------------------

    private void flag(CommandSender sender, Services s, String[] args) {
        if (args.length != 3) { messages.send(sender, "usage.flag"); return; }
        Optional<String> key = worldKey(sender, args[0]);
        if (key.isEmpty()) return;
        Optional<AtlasFlag> flag = AtlasFlag.fromKey(args[1]);
        if (flag.isEmpty()) {
            messages.send(sender, "flag.unknown", Map.of("flags", String.join(", ", flagKeys())));
            return;
        }
        String raw = args[2].toLowerCase(Locale.ROOT);
        if (!List.of("on", "true", "off", "false", "default").contains(raw)) { messages.send(sender, "usage.flag"); return; }
        Boolean value = raw.equals("default") ? null : raw.equals("on") || raw.equals("true");
        Map<String, String> values = Map.of("flag", flag.get().key(), "world", WorldNames.shortName(key.get()),
                "value", value == null ? "default" : value ? "on" : "off");
        s.rules().setFlag(key.get(), flag.get(), value).whenComplete((done, failure) ->
                messages.reply(sender, failure, "flag.set", values));
    }

    private void setting(CommandSender sender, Services s, String[] args) {
        if (args.length != 3) { messages.send(sender, "usage.set"); return; }
        Optional<String> key = worldKey(sender, args[0]);
        if (key.isEmpty()) return;
        String raw = args[2].toLowerCase(Locale.ROOT);
        AtlasRepository.Setting setting;
        Object value;
        try {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "difficulty" -> {
                    setting = AtlasRepository.Setting.DIFFICULTY;
                    value = raw.equals("default") ? null : Difficulty.valueOf(raw.toUpperCase(Locale.ROOT));
                }
                case "gamemode" -> {
                    setting = AtlasRepository.Setting.GAME_MODE;
                    value = raw.equals("none") ? null : GameMode.valueOf(raw.toUpperCase(Locale.ROOT));
                }
                case "time" -> {
                    setting = AtlasRepository.Setting.FIXED_TIME;
                    if (!raw.equals("unlock") && hasNoClock(s, key.get())) throw new IllegalArgumentException("no clock");
                    value = switch (raw) {
                        case "unlock" -> null;
                        case "day" -> 1000L;
                        case "noon" -> 6000L;
                        case "night" -> 13000L;
                        case "midnight" -> 18000L;
                        default -> {
                            long ticks = Long.parseLong(raw);
                            if (ticks < 0 || ticks > 23_999) throw new IllegalArgumentException("ticks");
                            yield ticks;
                        }
                    };
                }
                case "weather" -> {
                    setting = AtlasRepository.Setting.WEATHER;
                    value = raw.equals("none") ? null : WeatherMode.fromKey(raw).orElseThrow(IllegalArgumentException::new);
                }
                default -> { messages.send(sender, "usage.set"); return; }
            }
        } catch (IllegalArgumentException invalid) {
            messages.send(sender, "setting.invalid", Map.of("setting", args[1].toLowerCase(Locale.ROOT)));
            return;
        }
        Map<String, String> values = Map.of("setting", args[1].toLowerCase(Locale.ROOT),
                "world", WorldNames.shortName(key.get()), "value", value == null ? "none" : raw);
        s.rules().setSetting(key.get(), setting, value).whenComplete((done, failure) ->
                messages.reply(sender, failure, "setting.set", values));
    }

    /** Nether-type worlds have no world clock, so their time cannot be fixed. */
    private static boolean hasNoClock(Services s, String key) {
        return key.equals(WorldNames.NETHER) || s.rules().snapshot().worldOrBuiltIn(key).generator()
                .map(generator -> generator == WorldGenerator.NETHER).orElse(false);
    }

    // ---- selector, portals and keep-loaded regions --------------------------------------------

    private void selector(CommandSender sender, Services s) {
        if (!(sender instanceof Player player)) { messages.send(sender, "common.players-only"); return; }
        s.giveSelector().apply(player).whenComplete((given, failure) ->
                messages.send(sender, failure == null && Boolean.TRUE.equals(given) ? "selector.given" : "selector.pending"));
    }

    private Optional<SelectionService.Bounds> selection(CommandSender sender, Services s) {
        if (!(sender instanceof Player player)) { messages.send(sender, "common.players-only"); return Optional.empty(); }
        Optional<SelectionService.Bounds> bounds = s.selections().bounds(player.getUniqueId());
        if (bounds.isEmpty()) {
            messages.send(sender, s.selections().splitAcrossWorlds(player.getUniqueId())
                    ? "selection.different-worlds" : "selection.missing");
        }
        return bounds;
    }

    private void portal(CommandSender sender, Services s, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (s.portals().index().all().isEmpty()) { messages.send(sender, "portal.list-empty"); return; }
            s.portals().index().all().stream().sorted((a, b) -> a.name().compareTo(b.name())).forEach(portal ->
                    messages.send(sender, "portal.list-entry", Map.of("portal", portal.name(),
                            "world", WorldNames.shortName(portal.world()), "target", describe(portal.target()))));
            return;
        }
        if (args.length < 2 || !PORTAL.contains(action)) { messages.send(sender, "usage.portal"); return; }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!name.matches(NAME)) { messages.send(sender, "common.invalid-name"); return; }
        Map<String, String> named = Map.of("portal", name);
        switch (action) {
            case "create" -> {
                if (args.length != 3) { messages.send(sender, "usage.portal"); return; }
                Optional<String> target = worldKey(sender, args[2]);
                if (target.isEmpty()) return;
                selection(sender, s).ifPresent(bounds -> {
                    Portal portal = new Portal(name, bounds.world(), bounds.minX(), bounds.minY(), bounds.minZ(),
                            bounds.maxX(), bounds.maxY(), bounds.maxZ(), PortalTarget.spawn(target.get()),
                            s.config().defaultCooldownMillis(), Optional.empty(), Optional.empty(), false, Optional.empty());
                    s.portals().create(portal).whenComplete((created, failure) -> messages.reply(sender, failure,
                            Boolean.TRUE.equals(created) ? "portal.created" : "portal.exists",
                            Boolean.TRUE.equals(created) ? Map.of("portal", name, "world", WorldNames.shortName(target.get()))
                                    : named));
                });
            }
            case "delete" -> s.portals().delete(name).whenComplete((deleted, failure) -> messages.reply(sender, failure,
                    Boolean.TRUE.equals(deleted) ? "portal.deleted" : "portal.not-found", named));
            case "info" -> {
                Optional<Portal> found = s.portals().index().named(name);
                if (found.isEmpty()) { messages.send(sender, "portal.not-found", named); return; }
                Portal portal = found.get();
                messages.send(sender, "portal.info", Map.of("portal", name, "world", WorldNames.shortName(portal.world()),
                        "bounds", portal.minX() + "," + portal.minY() + "," + portal.minZ() + " -> "
                                + portal.maxX() + "," + portal.maxY() + "," + portal.maxZ(),
                        "target", describe(portal.target()), "cooldown", Integer.toString(portal.cooldownMillis()),
                        "sound", portal.sound().orElse("-"), "particle", portal.particle().orElse("-"),
                        "restricted", Boolean.toString(portal.restricted()), "fill", portal.fillBlock().orElse("-")));
            }
            case "target" -> portalTarget(sender, s, name, Arrays.copyOfRange(args, 2, args.length));
            case "cooldown" -> {
                if (args.length != 3 || !args[2].matches("\\d{1,6}") || Integer.parseInt(args[2]) > 600_000) {
                    messages.send(sender, "usage.portal");
                    return;
                }
                int millis = Integer.parseInt(args[2]);
                update(sender, s, name, portal -> portal.withCooldown(millis), "portal.cooldown-set",
                        Map.of("portal", name, "millis", args[2]));
            }
            case "sound" -> {
                if (args.length != 3) { messages.send(sender, "usage.portal"); return; }
                Optional<String> sound = args[2].equalsIgnoreCase("none") ? Optional.empty()
                        : Optional.of(args[2].toLowerCase(Locale.ROOT));
                if (sound.isPresent() && NamespacedKey.fromString(sound.get()) == null) {
                    messages.send(sender, "portal.invalid-sound");
                    return;
                }
                update(sender, s, name, portal -> portal.withSound(sound), "portal.sound-set",
                        Map.of("portal", name, "sound", sound.orElse("none")));
            }
            case "particle" -> {
                if (args.length != 3) { messages.send(sender, "usage.portal"); return; }
                Optional<String> particle = Optional.empty();
                if (!args[2].equalsIgnoreCase("none")) {
                    try {
                        Particle value = Particle.valueOf(args[2].toUpperCase(Locale.ROOT));
                        if (value.getDataType() != Void.class) throw new IllegalArgumentException("needs data");
                        particle = Optional.of(value.name());
                    } catch (IllegalArgumentException invalid) {
                        messages.send(sender, "portal.invalid-particle");
                        return;
                    }
                }
                Optional<String> chosen = particle;
                update(sender, s, name, portal -> portal.withParticle(chosen), "portal.particle-set",
                        Map.of("portal", name, "particle", chosen.orElse("none")));
            }
            case "restrict" -> {
                if (args.length != 3 || !List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) {
                    messages.send(sender, "usage.portal");
                    return;
                }
                boolean restricted = args[2].equalsIgnoreCase("on");
                update(sender, s, name, portal -> portal.withRestricted(restricted), "portal.restrict-set",
                        Map.of("portal", name, "value", restricted ? "on" : "off", "permission", "atlas.portal." + name));
            }
            case "fill" -> {
                if (args.length != 3) { messages.send(sender, "usage.portal"); return; }
                Optional<String> block = Optional.empty();
                if (!args[2].equalsIgnoreCase("none")) {
                    Material material = Material.matchMaterial(args[2]);
                    if (material == null || !material.isBlock() || material.isAir()) {
                        messages.send(sender, "portal.invalid-block");
                        return;
                    }
                    block = Optional.of(material.name());
                }
                Optional<Portal> found = s.portals().index().named(name);
                if (found.isPresent() && block.isPresent() && found.get().volume() > s.config().maxFillBlocks()) {
                    messages.send(sender, "portal.fill-too-large", Map.of("max", Integer.toString(s.config().maxFillBlocks())));
                    return;
                }
                Optional<String> chosen = block;
                update(sender, s, name, portal -> portal.withFill(chosen), "portal.fill-set",
                        Map.of("portal", name, "block", chosen.map(b -> b.toLowerCase(Locale.ROOT)).orElse("none")));
            }
            default -> messages.send(sender, "usage.portal");
        }
    }

    private void portalTarget(CommandSender sender, Services s, String name, String[] args) {
        PortalTarget target;
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            Optional<String> world = worldKey(sender, args[1]);
            if (world.isEmpty()) return;
            target = PortalTarget.spawn(world.get());
        } else if (args.length == 1 && args[0].equalsIgnoreCase("here")) {
            if (!(sender instanceof Player player)) { messages.send(sender, "common.players-only"); return; }
            Location at = player.getLocation();
            target = new PortalTarget(PortalTarget.Kind.LOCATION, Optional.empty(), RulesService.key(player.getWorld()),
                    Optional.of(new SpawnPoint(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch())));
        } else if ((args.length == 3 || args.length == 6 || args.length == 8) && args[0].equalsIgnoreCase("server")) {
            String region = args[1].toUpperCase(Locale.ROOT);
            if (!region.equals(s.config().otherRegion())) { messages.send(sender, "portal.invalid-region"); return; }
            Optional<String> world = worldKey(sender, args[2]);
            if (world.isEmpty()) return;
            Optional<SpawnPoint> point = Optional.empty();
            if (args.length >= 6) {
                try {
                    float yaw = args.length == 8 ? Float.parseFloat(args[6]) : 0f;
                    float pitch = args.length == 8 ? Float.parseFloat(args[7]) : 0f;
                    point = Optional.of(new SpawnPoint(Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                            Double.parseDouble(args[5]), yaw, pitch));
                } catch (NumberFormatException invalid) {
                    messages.send(sender, "usage.portal");
                    return;
                }
            }
            target = new PortalTarget(PortalTarget.Kind.SERVER, Optional.of(region), world.get(), point);
        } else {
            messages.send(sender, "usage.portal");
            return;
        }
        PortalTarget chosen = target;
        update(sender, s, name, portal -> portal.withTarget(chosen), "portal.target-set",
                Map.of("portal", name, "target", describe(chosen)));
    }

    private void update(CommandSender sender, Services s, String name, java.util.function.UnaryOperator<Portal> change,
                        String successKey, Map<String, String> values) {
        s.portals().update(name, change).whenComplete((updated, failure) -> {
            if (failure != null) messages.send(sender, "common.failed");
            else if (updated.isEmpty()) messages.send(sender, "portal.not-found", Map.of("portal", name));
            else messages.send(sender, successKey, values);
        });
    }

    private static String describe(PortalTarget target) {
        String world = WorldNames.shortName(target.world());
        String point = target.point().map(p -> " " + (int) p.x() + "," + (int) p.y() + "," + (int) p.z()).orElse(" spawn");
        return switch (target.kind()) {
            case SPAWN, LOCATION -> world + point;
            case SERVER -> target.region().orElse("?") + ":" + world + point;
        };
    }

    private void keepLoaded(CommandSender sender, Services s, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                List<KeepLoadedRegion> regions = s.keepLoaded().regions();
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    messages.send(sender, "keeploaded.list-header", Map.of("count", Integer.toString(regions.size()),
                            "tickets", Integer.toString(s.keepLoaded().ticketCount())));
                    for (KeepLoadedRegion region : regions) {
                        messages.send(sender, "keeploaded.list-entry", Map.of("region", region.name(),
                                "world", WorldNames.shortName(region.world()), "chunks", Long.toString(region.chunkCount()),
                                "minx", Integer.toString(region.minChunkX()), "minz", Integer.toString(region.minChunkZ()),
                                "maxx", Integer.toString(region.maxChunkX()), "maxz", Integer.toString(region.maxChunkZ())));
                    }
                });
            }
            case "set" -> {
                if (args.length != 2 || !args[1].toLowerCase(Locale.ROOT).matches("[a-z0-9_-]{1,64}")) {
                    messages.send(sender, "usage.keeploaded");
                    return;
                }
                String name = args[1].toLowerCase(Locale.ROOT);
                selection(sender, s).ifPresent(bounds -> {
                    KeepLoadedRegion region = KeepLoadedRegion.fromBlocks(name, bounds.world(),
                            bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
                    if (region.chunkCount() > s.config().maxChunksPerRegion()) {
                        messages.send(sender, "keeploaded.too-large", Map.of("chunks", Long.toString(region.chunkCount()),
                                "max", Integer.toString(s.config().maxChunksPerRegion())));
                        return;
                    }
                    s.keepLoaded().save(region).whenComplete((done, failure) -> messages.reply(sender,
                            failure, "keeploaded.saved", Map.of("region", name,
                                    "world", WorldNames.shortName(region.world()), "chunks", Long.toString(region.chunkCount()))));
                });
            }
            case "delete" -> {
                if (args.length != 2) { messages.send(sender, "usage.keeploaded"); return; }
                String name = args[1].toLowerCase(Locale.ROOT);
                s.keepLoaded().delete(name).whenComplete((deleted, failure) -> messages.reply(sender, failure,
                        Boolean.TRUE.equals(deleted) ? "keeploaded.deleted" : "keeploaded.not-found",
                        Map.of("region", name)));
            }
            default -> messages.send(sender, "usage.keeploaded");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Optional<String> worldKey(CommandSender sender, String input) {
        Optional<String> key = WorldNames.key(input);
        if (key.isEmpty()) messages.send(sender, "common.unknown-world", Map.of("world", input));
        return key;
    }

    private Optional<String> newWorld(CommandSender sender, String input) {
        String name = input.toLowerCase(Locale.ROOT);
        if (!WorldNames.isNewWorldName(name)) { messages.send(sender, "common.invalid-name"); return Optional.empty(); }
        return Optional.of(WorldNames.NAMESPACE + ":" + name);
    }

    private World loaded(String key) {
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : plugin.getServer().getWorld(namespaced);
    }

    private static UUID actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : new UUID(0L, 0L);
    }

    private static List<String> flagKeys() { return Arrays.stream(AtlasFlag.values()).map(AtlasFlag::key).toList(); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        Services ready = services;
        if (!sender.hasPermission(ADMIN) || args.length == 0) return List.of();
        List<String> worlds = worldNames(ready);
        String first = args[0].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 1 -> ROOT;
            case 2 -> switch (first) {
                case "world" -> WORLD;
                case "info", "tp", "spawn", "setspawn", "flag", "set" -> worlds;
                case "portal" -> PORTAL;
                case "keeploaded" -> KEEP_LOADED;
                default -> List.of();
            };
            case 3 -> switch (first) {
                case "world" -> switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "enable", "disable", "delete", "clone", "reset" -> worlds;
                    default -> List.of();
                };
                case "tp", "spawn" -> plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
                case "flag" -> flagKeys();
                case "set" -> SETTINGS;
                case "portal" -> args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("list") ? List.of()
                        : ready.portals().index().all().stream().map(Portal::name).toList();
                case "keeploaded" -> args[1].equalsIgnoreCase("delete")
                        ? ready.keepLoaded().regions().stream().map(KeepLoadedRegion::name).toList() : List.of();
                default -> List.of();
            };
            case 4 -> switch (first) {
                case "world" -> switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "create", "import" -> Arrays.stream(WorldGenerator.values()).map(WorldGenerator::key).toList();
                    case "delete" -> List.of("confirm");
                    case "reset" -> withConfirm(worlds);
                    default -> List.of();
                };
                case "flag" -> List.of("on", "off", "default");
                case "set" -> switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "difficulty" -> List.of("peaceful", "easy", "normal", "hard", "default");
                    case "gamemode" -> List.of("survival", "creative", "adventure", "spectator", "none");
                    case "time" -> List.of("day", "noon", "night", "midnight", "unlock");
                    case "weather" -> List.of("clear", "rain", "thunder", "none");
                    default -> List.of();
                };
                case "portal" -> switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "create" -> worlds;
                    case "target" -> List.of("spawn", "here", "server");
                    case "restrict" -> List.of("on", "off");
                    case "sound", "particle", "fill" -> List.of("none");
                    default -> List.of();
                };
                default -> List.of();
            };
            case 5 -> first.equals("world") && args[1].equalsIgnoreCase("reset") ? List.of("confirm")
                    : first.equals("portal") && args[1].equalsIgnoreCase("target")
                    ? (args[3].equalsIgnoreCase("spawn") ? worlds
                    : args[3].equalsIgnoreCase("server") ? List.of(ready.config().otherRegion()) : List.of())
                    : List.of();
            default -> List.of();
        };
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    private static List<String> withConfirm(List<String> worlds) {
        List<String> options = new ArrayList<>(worlds);
        options.add("confirm");
        return options;
    }

    private static List<String> worldNames(Services s) {
        List<String> names = new ArrayList<>(List.of("overworld", "the_nether", "the_end"));
        RulesSnapshot snapshot = s.rules().snapshot();
        if (snapshot != null) {
            snapshot.worlds().stream().map(WorldRecord::key).filter(key -> !WorldNames.builtIn(key))
                    .map(WorldNames::shortName).sorted().forEach(names::add);
        }
        return names;
    }
}
