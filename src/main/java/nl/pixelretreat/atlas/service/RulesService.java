package nl.pixelretreat.atlas.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.api.AtlasRules;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.RulesSnapshot;
import nl.pixelretreat.atlas.world.WeatherMode;
import nl.pixelretreat.atlas.world.WorldRecord;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WaterMob;
import org.bukkit.plugin.Plugin;

/**
 * Owns the current rules snapshot and applies world settings. Snapshot reads are lock-free;
 * world-wide changes (game rules, time, weather, difficulty, spawn) run on the global region
 * scheduler and player game modes on each player's entity scheduler.
 */
public final class RulesService implements AtlasRules {
    public static final String GAMEMODE_BYPASS = "atlas.gamemode.bypass";

    private final Plugin plugin;
    private final AtlasRepository repository;
    private final AtlasWorkers workers;
    private final AtlasConfig config;
    private final AtomicReference<RulesSnapshot> snapshot = new AtomicReference<>();

    public RulesService(Plugin plugin, AtlasRepository repository, AtlasWorkers workers, AtlasConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.workers = workers;
        this.config = config;
    }

    /** The dimension key Atlas uses for a loaded world. */
    public static String key(World world) { return world.getKey().toString(); }

    /** The current snapshot, or {@code null} before the first load. */
    public RulesSnapshot snapshot() { return snapshot.get(); }

    /** Loads every world record from the database and publishes a new snapshot. */
    public CompletableFuture<RulesSnapshot> reload() {
        return workers.submit(() -> {
            RulesSnapshot loaded = new RulesSnapshot(repository.loadWorlds(), config.defaultFlags());
            snapshot.set(loaded);
            return loaded;
        });
    }

    /** Stores a flag override ({@code null} clears it) and applies the world's rules. */
    public CompletableFuture<Void> setFlag(String worldKey, AtlasFlag flag, Boolean value) {
        return change(worldKey, false, () -> repository.setFlag(worldKey, flag, value));
    }

    /** Stores a setting ({@code null} clears it) and applies the world's rules. */
    public CompletableFuture<Void> setSetting(String worldKey, AtlasRepository.Setting setting, Object value) {
        return change(worldKey, setting == AtlasRepository.Setting.WEATHER,
                () -> repository.setSetting(worldKey, setting, value));
    }

    /** Stores the world's spawn and applies it. */
    public CompletableFuture<Void> setSpawn(String worldKey, nl.pixelretreat.atlas.world.SpawnPoint spawn) {
        return change(worldKey, false, () -> repository.setSpawn(worldKey, spawn));
    }

    private interface DatabaseChange { void run() throws Exception; }

    private CompletableFuture<Void> change(String worldKey, boolean weatherChanged, DatabaseChange change) {
        return workers.submit(() -> {
            change.run();
            snapshot.set(new RulesSnapshot(repository.loadWorlds(), config.defaultFlags()));
            return null;
        }).thenRun(() -> apply(worldKey, weatherChanged));
    }

    /** Builds a snapshot directly; used by startup and tests. */
    public void publish(Map<String, WorldRecord> worlds) {
        snapshot.set(new RulesSnapshot(worlds, config.defaultFlags()));
    }

    /** Applies the stored settings of every loaded world. */
    public void applyAll() {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            for (World world : plugin.getServer().getWorlds()) applyNow(world, false);
            applyGameModes();
        });
    }

    /** Applies one world's settings; {@code weatherChanged} forces the chosen weather once. */
    public void apply(String worldKey, boolean weatherChanged) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            World world = plugin.getServer().getWorld(org.bukkit.NamespacedKey.fromString(worldKey));
            if (world != null) applyNow(world, weatherChanged);
            applyGameModes();
        });
    }

    private void applyGameModes() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, ignored -> applyGameMode(player), null);
        }
    }

    private void applyNow(World world, boolean weatherChanged) {
        RulesSnapshot current = snapshot.get();
        if (current == null) return;
        String key = key(world);
        WorldRecord record = current.worldOrBuiltIn(key);
        boolean weatherCycle = current.flag(key, AtlasFlag.WEATHER);
        world.setGameRule(GameRules.ADVANCE_TIME, current.flag(key, AtlasFlag.TIME_CYCLE) && record.fixedTime().isEmpty());
        world.setGameRule(GameRules.ADVANCE_WEATHER, weatherCycle);
        world.setGameRule(GameRules.MOB_GRIEFING, current.flag(key, AtlasFlag.MOB_GRIEFING));
        world.setGameRule(GameRules.KEEP_INVENTORY, current.flag(key, AtlasFlag.KEEP_INVENTORY));
        record.difficulty().ifPresent(world::setDifficulty);
        // Nether-type dimensions have no world clock; the command refuses a fixed time there.
        if (world.getEnvironment() != World.Environment.NETHER) record.fixedTime().ifPresent(world::setTime);
        if (!weatherCycle || weatherChanged) {
            WeatherMode mode = record.weather().orElse(weatherCycle ? null : WeatherMode.CLEAR);
            if (mode != null) {
                world.setStorm(mode != WeatherMode.CLEAR);
                world.setThundering(mode == WeatherMode.THUNDER);
            }
        }
        record.spawn().ifPresent(spawn -> world.setSpawnLocation(
                new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch())));
    }

    /** Forces the world's game mode on a player unless the player may keep their own. Entity thread. */
    public void applyGameMode(Player player) {
        RulesSnapshot current = snapshot.get();
        if (current == null || player.hasPermission(GAMEMODE_BYPASS)) return;
        current.worldOrBuiltIn(key(player.getWorld())).gameMode().ifPresent(mode -> {
            if (player.getGameMode() != mode) player.setGameMode(mode);
        });
    }

    @Override public boolean ready() { return snapshot.get() != null; }

    @Override public boolean flagEnabled(World world, AtlasFlag flag) {
        RulesSnapshot current = snapshot.get();
        return current != null && world != null && flag != null && current.flag(key(world), flag);
    }

    @Override public boolean allowsSpawn(World world, EntityType type, SpawnOrigin origin) {
        RulesSnapshot current = snapshot.get();
        if (current == null || world == null || type == null || origin == null) return false;
        AtlasFlag flag = controllingFlag(type, origin);
        return flag == null || current.flag(key(world), flag);
    }

    /** The flag that decides a spawn, or {@code null} for creatures no mob flag governs. */
    public static AtlasFlag controllingFlag(EntityType type, SpawnOrigin origin) {
        Boolean hostile = hostile(type);
        if (hostile == null) return null;
        if (origin == SpawnOrigin.PLUGIN) return hostile ? AtlasFlag.PLUGIN_HOSTILE_MOBS : AtlasFlag.PLUGIN_FRIENDLY_MOBS;
        return hostile ? AtlasFlag.HOSTILE_MOBS : AtlasFlag.FRIENDLY_MOBS;
    }

    /** {@code true} hostile, {@code false} friendly, {@code null} neither (players, armor stands, ...). */
    static Boolean hostile(EntityType type) {
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null) return null;
        if (Enemy.class.isAssignableFrom(entityClass)) return true;
        if (Animals.class.isAssignableFrom(entityClass) || Ambient.class.isAssignableFrom(entityClass)
                || WaterMob.class.isAssignableFrom(entityClass) || Villager.class.isAssignableFrom(entityClass)
                || Golem.class.isAssignableFrom(entityClass)) return false;
        return switch (type) {
            case ALLAY, ARMADILLO, AXOLOTL, BAT, BEE, CAMEL, CAT, CHICKEN, COD, COPPER_GOLEM, COW,
                 DOLPHIN, DONKEY, FOX, FROG, GLOW_SQUID, GOAT, HAPPY_GHAST, HORSE, IRON_GOLEM,
                 LLAMA, MOOSHROOM, MULE, OCELOT, PANDA, PARROT, PIG, POLAR_BEAR, PUFFERFISH,
                 RABBIT, SALMON, SHEEP, SKELETON_HORSE, SNIFFER, SNOW_GOLEM, SQUID, STRIDER,
                 TADPOLE, TRADER_LLAMA, TROPICAL_FISH, TURTLE, VILLAGER, WANDERING_TRADER,
                 WOLF, ZOMBIE_HORSE -> false;
            default -> null;
        };
    }
}
