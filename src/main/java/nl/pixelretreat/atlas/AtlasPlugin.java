package nl.pixelretreat.atlas;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.veyra.api.VeyraCluster;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import nl.pixelretreat.atlas.api.AtlasRules;
import nl.pixelretreat.atlas.command.AtlasCommand;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.listener.PlayerListener;
import nl.pixelretreat.atlas.listener.PortalListener;
import nl.pixelretreat.atlas.listener.SelectorListener;
import nl.pixelretreat.atlas.listener.WorldRulesListener;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.message.BootstrapMessages;
import nl.pixelretreat.atlas.service.AtlasWorkers;
import nl.pixelretreat.atlas.service.KeepLoadedService;
import nl.pixelretreat.atlas.service.PortalService;
import nl.pixelretreat.atlas.service.RulesService;
import nl.pixelretreat.atlas.service.SelectionService;
import nl.pixelretreat.atlas.service.StartupOperations;
import nl.pixelretreat.atlas.service.TeleportService;
import nl.pixelretreat.atlas.service.WorldAdminService;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.DatapackWriter;
import nl.pixelretreat.atlas.world.WorldFolders;
import nl.pixelretreat.campfire.api.CampfireDelivery;
import nl.pixelretreat.campfire.api.CampfireMessages;
import nl.pixelretreat.campfire.api.MessageCatalog;
import nl.pixelretreat.closet.api.ClosetGrants;
import nl.pixelretreat.closet.api.ClosetService;
import nl.pixelretreat.closet.api.ContentRegistration;
import nl.pixelretreat.closet.api.ItemGrantRequest;
import nl.pixelretreat.closet.api.ItemGrantResult;
import nl.pixelretreat.closet.api.ItemRequest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Boots Atlas. Queued world-folder work runs in {@link #onLoad()}, before Minecraft loads any
 * world. Everything else starts asynchronously in {@link #onEnable()}; commands answer "still
 * loading" and guarded gameplay is refused until the rules are loaded.
 */
public final class AtlasPlugin extends JavaPlugin {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile AtlasConfig config;
    private volatile HikariDataSource database;
    private volatile AtlasRepository repository;
    private volatile Path levelFolder;
    private volatile Throwable loadFailure;
    private volatile String bootstrapFailure;
    private volatile List<StartupOperations.Outcome> startupOutcomes = List.of();
    private volatile boolean datapackChangedAtStartup;
    private volatile AtlasWorkers workers;
    private volatile CampfireMessages campfire;
    private volatile MessageCatalog catalog;
    private volatile ClosetService closet;
    private volatile ContentRegistration closetRegistration;
    private volatile KeepLoadedService keepLoaded;

    /** Opens the database and runs queued folder work while no world is loaded yet. */
    @Override public void onLoad() {
        try {
            bootstrapFailure = BootstrapMessages.startupFailure(getResource("messages.yml"));
            ensureResource("config.yml");
            AtlasConfig settings = AtlasConfig.read(YamlConfiguration.loadConfiguration(
                    getDataFolder().toPath().resolve("config.yml").toFile()));
            config = settings;
            levelFolder = levelFolder();
            database = openDatabase(settings);
            repository = new AtlasRepository(database, settings.region());
            repository.initialize();
            DatapackWriter writer = datapack(settings);
            startupOutcomes = new StartupOperations(repository, folders(settings), writer.declaredWorlds()).run();
            datapackChangedAtStartup = writer.synchronize(repository.loadWorlds().values());
        } catch (Exception failure) {
            loadFailure = failure;
        }
    }

    private Path levelFolder() throws IOException {
        Path properties = Path.of("server.properties");
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(properties, StandardCharsets.UTF_8)) { values.load(reader); }
        String level = values.getProperty("level-name");
        if (level == null || level.isBlank()) throw new IOException("server.properties has no level-name");
        return getServer().getWorldContainer().toPath().resolve(level.trim()).toAbsolutePath().normalize();
    }

    private WorldFolders folders(AtlasConfig settings) {
        return new WorldFolders(levelFolder.resolve("dimensions"), settings.workerThreads());
    }

    private DatapackWriter datapack(AtlasConfig settings) {
        return new DatapackWriter(levelFolder.resolve("datapacks").resolve("atlas"), settings.packFormat());
    }

    private void ensureResource(String name) throws IOException {
        Files.createDirectories(getDataFolder().toPath());
        Path target = getDataFolder().toPath().resolve(name);
        if (Files.exists(target)) return;
        try (var input = getResource(name)) {
            if (input == null) throw new IOException("Missing Atlas resource " + name);
            Files.copy(input, target);
        }
    }

    private static HikariDataSource openDatabase(AtlasConfig settings) {
        var hikari = new HikariConfig();
        hikari.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        hikari.setJdbcUrl("jdbc:mariadb://" + settings.databaseHost() + ":" + settings.databasePort()
                + "/" + settings.databaseName() + "?sslMode=" + settings.databaseSslMode()
                + "&connectTimeout=2000&socketTimeout=5000");
        hikari.setUsername(settings.databaseUsername());
        hikari.setPassword(settings.databasePassword());
        hikari.setMaximumPoolSize(Math.min(settings.workerThreads(), 4));
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(3000);
        hikari.setValidationTimeout(1000);
        hikari.setPoolName("Atlas");
        return new HikariDataSource(hikari);
    }

    @Override public void onEnable() {
        if (loadFailure != null) { fail(loadFailure); return; }
        AtlasConfig settings = config;
        workers = new AtlasWorkers(settings.workerThreads(), settings.workerQueueSize());
        RulesService rules = new RulesService(this, repository, workers, settings);
        getServer().getPluginManager().registerEvents(new WorldRulesListener(rules), this);
        getServer().getAsyncScheduler().runNow(this, ignored -> {
            try {
                ensureResource("messages.yml");
                ensureResource("closet.yml");
                getServer().getGlobalRegionScheduler().run(this, task -> connect(settings, rules));
            } catch (Exception failure) {
                fail(failure);
            }
        });
    }

    /** How many times Atlas waits for a network contract before giving up, and the gap in ticks. */
    private static final int CONTRACT_ATTEMPTS = 60;
    private static final long CONTRACT_INTERVAL_TICKS = 20L;

    private void connect(AtlasConfig settings, RulesService rules) {
        connect(settings, rules, 0);
    }

    /**
     * Waits for Postbox, Campfire and Closet to publish their contracts. They register during their own
     * asynchronous start, which happens after Atlas enables, so this retries with a bounded wait and
     * reports which contract never arrived. A region mismatch is a configuration error and fails at once.
     *
     * @param settings the plugin settings
     * @param rules    the world rules service
     * @param attempt  how many tries have happened
     */
    private void connect(AtlasConfig settings, RulesService rules, int attempt) {
        if (stopped.get() || !isEnabled()) return;
        VeyraCluster link = getServer().getServicesManager().load(VeyraCluster.class);
        CampfireMessages messages = getServer().getServicesManager().load(CampfireMessages.class);
        ClosetService items = getServer().getServicesManager().load(ClosetService.class);
        if (link != null && link.isEnabled() && link.localNode().isPresent()
                && !link.localNode().get().name().equalsIgnoreCase(settings.region())) {
            fail(new IllegalStateException("Postbox is " + link.localNode().get().name()
                + " but Atlas expects " + settings.region()));
            return;
        }
        String late = lateContracts(link, messages, items);
        if (late != null) {
            if (attempt >= CONTRACT_ATTEMPTS) {
                fail(new IllegalStateException("Missing network contracts after "
                    + (CONTRACT_ATTEMPTS * CONTRACT_INTERVAL_TICKS / 20) + "s: " + late));
                return;
            }
            getServer().getGlobalRegionScheduler().runDelayed(this,
                task -> connect(settings, rules, attempt + 1), CONTRACT_INTERVAL_TICKS);
            return;
        }
        campfire = messages;
        closet = items;
        messages.register(this).toCompletableFuture()
                .thenApply(registered -> {
                    AtlasMessages.validateCatalog(registered);
                    catalog = registered;
                    return registered;
                })
                .thenCompose(registered -> items.register(this).toCompletableFuture())
                .thenCompose(registration -> {
                    closetRegistration = registration;
                    return rules.reload();
                })
                .whenComplete((loaded, failure) -> {
                    if (failure != null) { fail(failure); return; }
                    getServer().getGlobalRegionScheduler().run(this, task -> install(settings, rules, 0));
                });
    }

    /** Names the contracts that have not been published yet, or null when all of them are ready. */
    private static String lateContracts(VeyraCluster link, CampfireMessages messages, ClosetService items) {
        List<String> late = new ArrayList<>();
        if (link == null) late.add("Postbox cluster link");
        else if (!link.isEnabled()) late.add("Postbox cluster link (not enabled)");
        else if (link.localNode().isEmpty()) late.add("Postbox local node");
        if (messages == null) late.add("Campfire messages");
        if (items == null) late.add("Closet service");
        return late.isEmpty() ? null : String.join(", ", late);
    }

    private void install(AtlasConfig settings, RulesService rules, int attempt) {
        if (stopped.get() || !isEnabled()) return;
        CampfireDelivery delivery = getServer().getServicesManager().load(CampfireDelivery.class);
        ClosetGrants grants = getServer().getServicesManager().load(ClosetGrants.class);
        if (delivery == null || grants == null) {
            if (attempt >= 30) { fail(new IllegalStateException("Campfire delivery or Closet grants unavailable")); return; }
            getServer().getGlobalRegionScheduler().runDelayed(this, task -> install(settings, rules, attempt + 1), 20L);
            return;
        }
        try {
            AtlasMessages messages = new AtlasMessages(this, Objects.requireNonNull(catalog), delivery);
            reportStartup(messages);
            SelectionService selections = new SelectionService();
            TeleportService teleports = new TeleportService(this, rules, settings);
            PortalService portals = new PortalService(this, repository, workers, settings, teleports);
            KeepLoadedService regions = new KeepLoadedService(this, repository, workers);
            keepLoaded = regions;
            WorldAdminService worlds = new WorldAdminService(repository, workers, rules, datapack(settings),
                    folders(settings), portals, regions);
            rules.applyAll();
            CompletableFuture.allOf(portals.reload(), regions.reload()).whenComplete((done, failure) -> {
                if (failure != null) { fail(failure); return; }
                getServer().getGlobalRegionScheduler().run(this, task -> {
                    if (stopped.get() || !isEnabled()) return;
                    getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
                    PlayerListener players = new PlayerListener(this, rules, portals, selections, messages);
                    getServer().getPluginManager().registerEvents(players, this);
                    getServer().getPluginManager().registerEvents(new PortalListener(rules, portals, messages), this);
                    getServer().getPluginManager().registerEvents(new SelectorListener(closet, selections, messages), this);
                    getServer().getServicesManager().register(AtlasRules.class, rules, this, ServicePriority.Normal);
                    AtlasCommand command = new AtlasCommand(this, messages, new AtlasCommand.Services(settings, rules,
                            worlds, teleports, portals, regions, selections, player -> giveSelector(grants, player),
                            () -> reloadAll(rules, portals, regions)));
                    Objects.requireNonNull(getCommand("atlas")).setExecutor(command);
                    Objects.requireNonNull(getCommand("atlas")).setTabCompleter(command);
                    for (Player online : getServer().getOnlinePlayers()) {
                        online.getScheduler().run(this, ignored -> players.arrived(online), null);
                    }
                    getLogger().info(messages.plain("startup.ready"));
                });
            });
            getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
                AtlasWorkers current = workers;
                if (current != null && !stopped.get()) current.submit(repository::purgeTickets).exceptionally(failure -> 0);
            }, 10, 60, TimeUnit.MINUTES);
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private CompletableFuture<Boolean> giveSelector(ClosetGrants grants, Player player) {
        return grants.grant(this, new ItemGrantRequest(UUID.randomUUID(), player.getUniqueId(),
                        Optional.of(player.getUniqueId()), "atlas.selector", ItemRequest.of(SelectorListener.SELECTOR, 1)))
                .toCompletableFuture()
                .thenApply(result -> result != null && result.status() == ItemGrantResult.Status.APPLIED);
    }

    private CompletableFuture<Void> reloadAll(RulesService rules, PortalService portals, KeepLoadedService regions) {
        return campfire.reload(this, AtlasMessages::validateCatalog).toCompletableFuture()
                .thenCompose(ignored -> rules.reload())
                .thenCompose(ignored -> CompletableFuture.allOf(portals.reload(), regions.reload()))
                .thenRun(rules::applyAll);
    }

    private void reportStartup(AtlasMessages messages) {
        for (StartupOperations.Outcome outcome : startupOutcomes) {
            Map<String, String> values = Map.of("id", Long.toString(outcome.operation().id()),
                    "kind", outcome.operation().kind().name().toLowerCase(java.util.Locale.ROOT),
                    "world", outcome.operation().world());
            if (outcome.success()) getLogger().info(messages.plain("startup.operation-done", values));
            else {
                Map<String, String> withDetail = new java.util.HashMap<>(values);
                withDetail.put("detail", outcome.detail());
                getLogger().warning(messages.plain("startup.operation-failed", withDetail));
            }
        }
        if (datapackChangedAtStartup) getLogger().warning(messages.plain("startup.datapack-changed"));
    }

    private void fail(Throwable failure) {
        if (stopped.get() || !failed.compareAndSet(false, true)) return;
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        loadFailure = cause; // Diagnostic stays internal; neither cause text nor credentials reach the console.
        String text = bootstrapFailure;
        try {
            if (catalog != null) text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(catalog.get("startup.failed"));
        } catch (RuntimeException ignored) { /* A broken catalog cannot render itself. */ }
        if (text != null) getLogger().severe(text);
        getServer().getGlobalRegionScheduler().run(this, ignored -> {
            if (isEnabled()) getServer().getPluginManager().disablePlugin(this);
        });
    }

    /** Releases tickets, workers and service registrations; never touches the server lifecycle. */
    @Override public void onDisable() {
        stopped.set(true);
        getServer().getServicesManager().unregisterAll(this);
        if (keepLoaded != null) keepLoaded.releaseAll();
        if (workers != null) workers.close();
        if (database != null) database.close();
        if (closetRegistration != null) closetRegistration.close();
        if (closet != null) closet.unregister(this);
        if (campfire != null) campfire.unregister(this);
    }
}
