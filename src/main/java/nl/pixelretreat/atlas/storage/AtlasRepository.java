package nl.pixelretreat.atlas.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.keeploaded.KeepLoadedRegion;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.SpawnPoint;
import nl.pixelretreat.atlas.world.WeatherMode;
import nl.pixelretreat.atlas.world.WorldGenerator;
import nl.pixelretreat.atlas.world.WorldRecord;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;

/**
 * All Atlas MariaDB access. Every row belongs to one server region, so EU and NA share the
 * database without sharing worlds. Methods block and must run on {@code AtlasWorkers}, or at
 * startup before any world is ticking.
 */
public final class AtlasRepository {
    private final DataSource database;
    private final String server;

    public AtlasRepository(DataSource database, String server) {
        this.database = database;
        this.server = server;
    }

    /** Creates the tables this plugin uses. */
    public void initialize() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlas_world (
                      server VARCHAR(8) NOT NULL, world VARCHAR(100) NOT NULL, generator VARCHAR(16) NULL,
                      enabled BOOLEAN NOT NULL, reset_source VARCHAR(100) NULL,
                      spawn_x DOUBLE NULL, spawn_y DOUBLE NULL, spawn_z DOUBLE NULL,
                      spawn_yaw FLOAT NULL, spawn_pitch FLOAT NULL,
                      difficulty VARCHAR(16) NULL, game_mode VARCHAR(16) NULL, fixed_time BIGINT NULL,
                      weather VARCHAR(16) NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      PRIMARY KEY (server, world))""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlas_world_flag (
                      server VARCHAR(8) NOT NULL, world VARCHAR(100) NOT NULL, flag VARCHAR(32) NOT NULL,
                      enabled BOOLEAN NOT NULL, PRIMARY KEY (server, world, flag))""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlas_portal (
                      server VARCHAR(8) NOT NULL, name VARCHAR(48) NOT NULL, world VARCHAR(100) NOT NULL,
                      min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL,
                      max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL,
                      target_kind VARCHAR(16) NOT NULL, target_region VARCHAR(8) NULL, target_world VARCHAR(100) NOT NULL,
                      target_x DOUBLE NULL, target_y DOUBLE NULL, target_z DOUBLE NULL,
                      target_yaw FLOAT NULL, target_pitch FLOAT NULL,
                      cooldown_ms INT NOT NULL, sound VARCHAR(100) NULL, particle VARCHAR(64) NULL,
                      restricted BOOLEAN NOT NULL, fill_block VARCHAR(64) NULL,
                      PRIMARY KEY (server, name))""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlas_keep_loaded_region (
                      server VARCHAR(8) NOT NULL, name VARCHAR(64) NOT NULL, world VARCHAR(100) NOT NULL,
                      min_chunk_x INT NOT NULL, min_chunk_z INT NOT NULL, max_chunk_x INT NOT NULL, max_chunk_z INT NOT NULL,
                      PRIMARY KEY (server, name))""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlas_pending_operation (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, server VARCHAR(8) NOT NULL,
                      kind VARCHAR(16) NOT NULL, world VARCHAR(100) NOT NULL, source VARCHAR(100) NULL,
                      requested_by CHAR(36) NOT NULL, requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      state VARCHAR(16) NOT NULL, result VARCHAR(255) NULL, finished_at TIMESTAMP(3) NULL,
                      KEY atlas_pending_server_state (server, state))""");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS atlas_travel_ticket (
                      id CHAR(36) NOT NULL PRIMARY KEY, player CHAR(36) NOT NULL, origin_region VARCHAR(8) NOT NULL,
                      target_region VARCHAR(8) NOT NULL, portal VARCHAR(48) NOT NULL, target_world VARCHAR(100) NOT NULL,
                      target_x DOUBLE NULL, target_y DOUBLE NULL, target_z DOUBLE NULL,
                      target_yaw FLOAT NULL, target_pitch FLOAT NULL,
                      created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), expires_at TIMESTAMP(3) NOT NULL,
                      consumed_at TIMESTAMP(3) NULL,
                      KEY atlas_ticket_arrival (player, target_region, consumed_at))""");
        }
    }

    // ---- Worlds -------------------------------------------------------------------------------

    /** Every world record of this server, with its flag overrides. */
    public Map<String, WorldRecord> loadWorlds() throws SQLException {
        Map<String, Map<AtlasFlag, Boolean>> flags = new HashMap<>();
        Map<String, WorldRecord> worlds = new HashMap<>();
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT world, flag, enabled FROM atlas_world_flag WHERE server = ?")) {
                query.setString(1, server);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        Optional<AtlasFlag> flag = AtlasFlag.fromKey(rows.getString("flag"));
                        if (flag.isEmpty()) throw new SQLException("Unknown Atlas flag in database: " + rows.getString("flag"));
                        flags.computeIfAbsent(rows.getString("world"), ignored -> new EnumMap<>(AtlasFlag.class))
                                .put(flag.get(), rows.getBoolean("enabled"));
                    }
                }
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM atlas_world WHERE server = ?")) {
                query.setString(1, server);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String key = rows.getString("world");
                        worlds.put(key, readWorld(rows, flags.getOrDefault(key, Map.of())));
                    }
                }
            }
        }
        for (var entry : flags.entrySet()) {
            if (!worlds.containsKey(entry.getKey())) throw new SQLException("Flags without a world row: " + entry.getKey());
        }
        return worlds;
    }

    private static WorldRecord readWorld(ResultSet rows, Map<AtlasFlag, Boolean> flags) throws SQLException {
        Optional<SpawnPoint> spawn = rows.getObject("spawn_x") == null ? Optional.empty()
                : Optional.of(new SpawnPoint(rows.getDouble("spawn_x"), rows.getDouble("spawn_y"),
                rows.getDouble("spawn_z"), rows.getFloat("spawn_yaw"), rows.getFloat("spawn_pitch")));
        Long fixedTime = rows.getObject("fixed_time") == null ? null : rows.getLong("fixed_time");
        return new WorldRecord(rows.getString("world"),
                optionalEnum(rows.getString("generator"), WorldGenerator.class), rows.getBoolean("enabled"),
                Optional.ofNullable(rows.getString("reset_source")), spawn,
                optionalEnum(rows.getString("difficulty"), Difficulty.class),
                optionalEnum(rows.getString("game_mode"), GameMode.class), Optional.ofNullable(fixedTime),
                optionalEnum(rows.getString("weather"), WeatherMode.class), flags);
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(String value, Class<E> type) throws SQLException {
        if (value == null) return Optional.empty();
        try { return Optional.of(Enum.valueOf(type, value)); }
        catch (IllegalArgumentException invalid) { throw new SQLException("Invalid " + type.getSimpleName() + ": " + value); }
    }

    /** Inserts a new Atlas world; returns false if the key is already used on this server. */
    public boolean insertWorld(String key, WorldGenerator generator, Optional<String> resetSource) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO atlas_world (server, world, generator, enabled, reset_source) VALUES (?, ?, ?, TRUE, ?)")) {
            insert.setString(1, server);
            insert.setString(2, key);
            insert.setString(3, generator.name());
            insert.setString(4, resetSource.orElse(null));
            return insert.executeUpdate() == 1;
        }
    }

    /** Switches whether an Atlas world is declared in the data pack. */
    public boolean setEnabled(String key, boolean enabled) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE atlas_world SET enabled = ? WHERE server = ? AND world = ? AND generator IS NOT NULL")) {
            update.setBoolean(1, enabled);
            update.setString(2, server);
            update.setString(3, key);
            return update.executeUpdate() == 1;
        }
    }

    /** Sets or clears ({@code null}) one flag override. */
    public void setFlag(String key, AtlasFlag flag, Boolean enabled) throws SQLException {
        try (Connection connection = database.getConnection()) {
            ensureWorldRow(connection, key);
            if (enabled == null) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM atlas_world_flag WHERE server = ? AND world = ? AND flag = ?")) {
                    delete.setString(1, server);
                    delete.setString(2, key);
                    delete.setString(3, flag.key());
                    delete.executeUpdate();
                }
                return;
            }
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO atlas_world_flag (server, world, flag, enabled) VALUES (?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)")) {
                upsert.setString(1, server);
                upsert.setString(2, key);
                upsert.setString(3, flag.key());
                upsert.setBoolean(4, enabled);
                upsert.executeUpdate();
            }
        }
    }

    /** A world setting column that staff can change. */
    public enum Setting { DIFFICULTY("difficulty"), GAME_MODE("game_mode"), FIXED_TIME("fixed_time"), WEATHER("weather");
        private final String column;
        Setting(String column) { this.column = column; }
    }

    /** Stores a setting value, or clears it with {@code null}. */
    public void setSetting(String key, Setting setting, Object value) throws SQLException {
        try (Connection connection = database.getConnection()) {
            ensureWorldRow(connection, key);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE atlas_world SET " + setting.column + " = ? WHERE server = ? AND world = ?")) {
                if (value == null) update.setNull(1, setting == Setting.FIXED_TIME ? Types.BIGINT : Types.VARCHAR);
                else if (value instanceof Long ticks) update.setLong(1, ticks);
                else if (value instanceof Enum<?> constant) update.setString(1, constant.name());
                else throw new IllegalArgumentException("Unsupported setting value");
                update.setString(2, server);
                update.setString(3, key);
                update.executeUpdate();
            }
        }
    }

    /** Stores the world's spawn point. */
    public void setSpawn(String key, SpawnPoint spawn) throws SQLException {
        try (Connection connection = database.getConnection()) {
            ensureWorldRow(connection, key);
            try (PreparedStatement update = connection.prepareStatement("UPDATE atlas_world SET spawn_x = ?, spawn_y = ?, "
                    + "spawn_z = ?, spawn_yaw = ?, spawn_pitch = ? WHERE server = ? AND world = ?")) {
                update.setDouble(1, spawn.x());
                update.setDouble(2, spawn.y());
                update.setDouble(3, spawn.z());
                update.setFloat(4, spawn.yaw());
                update.setFloat(5, spawn.pitch());
                update.setString(6, server);
                update.setString(7, key);
                update.executeUpdate();
            }
        }
    }

    /** Removes an Atlas world's rows after its folder was deleted. */
    public void deleteWorld(String key) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("atlas_world_flag", "atlas_world")) {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE server = ? AND world = ?")) {
                        delete.setString(1, server);
                        delete.setString(2, key);
                        delete.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void ensureWorldRow(Connection connection, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO atlas_world (server, world, generator, enabled) VALUES (?, ?, NULL, TRUE)")) {
            insert.setString(1, server);
            insert.setString(2, key);
            insert.executeUpdate();
        }
    }

    // ---- Pending folder operations -----------------------------------------------------------

    /** Queues folder work for the next start and returns its id. */
    public long queue(PendingOperation.Kind kind, String world, Optional<String> source, UUID requestedBy) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO atlas_pending_operation (server, kind, world, source, requested_by, state) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING')", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, server);
            insert.setString(2, kind.name());
            insert.setString(3, world);
            insert.setString(4, source.orElse(null));
            insert.setString(5, requestedBy.toString());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No id for queued Atlas operation");
                return keys.getLong(1);
            }
        }
    }

    /** Pending operations of this server, oldest first. */
    public List<PendingOperation> pending() throws SQLException {
        List<PendingOperation> operations = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM atlas_pending_operation WHERE server = ? AND state = 'PENDING' ORDER BY id")) {
            query.setString(1, server);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    operations.add(new PendingOperation(rows.getLong("id"),
                            PendingOperation.Kind.valueOf(rows.getString("kind")), rows.getString("world"),
                            Optional.ofNullable(rows.getString("source")),
                            UUID.fromString(rows.getString("requested_by")),
                            rows.getTimestamp("requested_at").toInstant()));
                }
            }
        }
        return operations;
    }

    /** Records the outcome of a pending operation. Only a pending row can change. */
    public boolean finish(long id, boolean success, String result) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE atlas_pending_operation SET state = ?, result = ?, finished_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE id = ? AND server = ? AND state = 'PENDING'")) {
            update.setString(1, success ? "DONE" : "FAILED");
            update.setString(2, result.length() > 255 ? result.substring(0, 255) : result);
            update.setLong(3, id);
            update.setString(4, server);
            return update.executeUpdate() == 1;
        }
    }

    /** Cancels a pending operation; returns it when this call cancelled it. */
    public Optional<PendingOperation> cancel(long id) throws SQLException {
        Optional<PendingOperation> operation = pending().stream().filter(op -> op.id() == id).findFirst();
        if (operation.isEmpty()) return Optional.empty();
        try (Connection connection = database.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE atlas_pending_operation SET state = 'CANCELLED', finished_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE id = ? AND server = ? AND state = 'PENDING'")) {
            update.setLong(1, id);
            update.setString(2, server);
            return update.executeUpdate() == 1 ? operation : Optional.empty();
        }
    }

    // ---- Portals ------------------------------------------------------------------------------

    /** Every portal of this server. */
    public List<Portal> loadPortals() throws SQLException {
        List<Portal> portals = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM atlas_portal WHERE server = ?")) {
            query.setString(1, server);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    Optional<SpawnPoint> point = rows.getObject("target_x") == null ? Optional.empty()
                            : Optional.of(new SpawnPoint(rows.getDouble("target_x"), rows.getDouble("target_y"),
                            rows.getDouble("target_z"), rows.getFloat("target_yaw"), rows.getFloat("target_pitch")));
                    PortalTarget target = new PortalTarget(PortalTarget.Kind.valueOf(rows.getString("target_kind")),
                            Optional.ofNullable(rows.getString("target_region")), rows.getString("target_world"), point);
                    portals.add(new Portal(rows.getString("name"), rows.getString("world"),
                            rows.getInt("min_x"), rows.getInt("min_y"), rows.getInt("min_z"),
                            rows.getInt("max_x"), rows.getInt("max_y"), rows.getInt("max_z"), target,
                            rows.getInt("cooldown_ms"), Optional.ofNullable(rows.getString("sound")),
                            Optional.ofNullable(rows.getString("particle")), rows.getBoolean("restricted"),
                            Optional.ofNullable(rows.getString("fill_block"))));
                }
            }
        }
        return portals;
    }

    /** Inserts a new portal or replaces an existing one with the same name. */
    public void savePortal(Portal portal) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement upsert = connection.prepareStatement("""
                REPLACE INTO atlas_portal (server, name, world, min_x, min_y, min_z, max_x, max_y, max_z,
                  target_kind, target_region, target_world, target_x, target_y, target_z, target_yaw, target_pitch,
                  cooldown_ms, sound, particle, restricted, fill_block)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            upsert.setString(1, server);
            upsert.setString(2, portal.name());
            upsert.setString(3, portal.world());
            upsert.setInt(4, portal.minX());
            upsert.setInt(5, portal.minY());
            upsert.setInt(6, portal.minZ());
            upsert.setInt(7, portal.maxX());
            upsert.setInt(8, portal.maxY());
            upsert.setInt(9, portal.maxZ());
            PortalTarget target = portal.target();
            upsert.setString(10, target.kind().name());
            upsert.setString(11, target.region().orElse(null));
            upsert.setString(12, target.world());
            setPoint(upsert, 13, target.point());
            upsert.setInt(18, portal.cooldownMillis());
            upsert.setString(19, portal.sound().orElse(null));
            upsert.setString(20, portal.particle().orElse(null));
            upsert.setBoolean(21, portal.restricted());
            upsert.setString(22, portal.fillBlock().orElse(null));
            upsert.executeUpdate();
        }
    }

    /** Deletes a portal; returns false when it did not exist. */
    public boolean deletePortal(String name) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM atlas_portal WHERE server = ? AND name = ?")) {
            delete.setString(1, server);
            delete.setString(2, name);
            return delete.executeUpdate() == 1;
        }
    }

    private static void setPoint(PreparedStatement statement, int first, Optional<SpawnPoint> point) throws SQLException {
        if (point.isPresent()) {
            statement.setDouble(first, point.get().x());
            statement.setDouble(first + 1, point.get().y());
            statement.setDouble(first + 2, point.get().z());
            statement.setFloat(first + 3, point.get().yaw());
            statement.setFloat(first + 4, point.get().pitch());
        } else {
            for (int index = 0; index < 3; index++) statement.setNull(first + index, Types.DOUBLE);
            statement.setNull(first + 3, Types.FLOAT);
            statement.setNull(first + 4, Types.FLOAT);
        }
    }

    // ---- Keep-loaded regions ------------------------------------------------------------------

    /** Every keep-loaded region of this server. */
    public List<KeepLoadedRegion> loadRegions() throws SQLException {
        List<KeepLoadedRegion> regions = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM atlas_keep_loaded_region WHERE server = ?")) {
            query.setString(1, server);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    regions.add(new KeepLoadedRegion(rows.getString("name"), rows.getString("world"),
                            rows.getInt("min_chunk_x"), rows.getInt("min_chunk_z"),
                            rows.getInt("max_chunk_x"), rows.getInt("max_chunk_z")));
                }
            }
        }
        return regions;
    }

    /** Inserts or replaces a keep-loaded region. */
    public void saveRegion(KeepLoadedRegion region) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement upsert = connection.prepareStatement(
                "REPLACE INTO atlas_keep_loaded_region (server, name, world, min_chunk_x, min_chunk_z, max_chunk_x, max_chunk_z) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            upsert.setString(1, server);
            upsert.setString(2, region.name());
            upsert.setString(3, region.world());
            upsert.setInt(4, region.minChunkX());
            upsert.setInt(5, region.minChunkZ());
            upsert.setInt(6, region.maxChunkX());
            upsert.setInt(7, region.maxChunkZ());
            upsert.executeUpdate();
        }
    }

    /** Deletes a keep-loaded region; returns false when it did not exist. */
    public boolean deleteRegion(String name) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM atlas_keep_loaded_region WHERE server = ? AND name = ?")) {
            delete.setString(1, server);
            delete.setString(2, name);
            return delete.executeUpdate() == 1;
        }
    }

    // ---- Cross-server travel tickets ----------------------------------------------------------

    /** Records a trip to another region before the player is sent there. */
    public void issueTicket(TravelTicket ticket, String targetRegion, Instant expiresAt) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO atlas_travel_ticket (id, player, origin_region, target_region, portal, target_world,
                  target_x, target_y, target_z, target_yaw, target_pitch, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            insert.setString(1, ticket.id().toString());
            insert.setString(2, ticket.player().toString());
            insert.setString(3, server);
            insert.setString(4, targetRegion);
            insert.setString(5, ticket.portal());
            insert.setString(6, ticket.world());
            setPoint(insert, 7, ticket.point());
            insert.setTimestamp(12, Timestamp.from(expiresAt));
            insert.executeUpdate();
        }
    }

    /** Marks a ticket as unused because the player could not be sent; returns whether it changed. */
    public boolean withdrawTicket(UUID id) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM atlas_travel_ticket WHERE id = ? AND consumed_at IS NULL")) {
            delete.setString(1, id.toString());
            return delete.executeUpdate() == 1;
        }
    }

    /**
     * Consumes the newest valid ticket for a player arriving on this server. The conditional
     * update makes sure a ticket is used at most once, even if both servers race.
     */
    public Optional<TravelTicket> takeTicket(UUID player) throws SQLException {
        try (Connection connection = database.getConnection()) {
            TravelTicket ticket;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM atlas_travel_ticket WHERE player = ? AND target_region = ? AND consumed_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP(3) ORDER BY created_at DESC LIMIT 1""")) {
                query.setString(1, player.toString());
                query.setString(2, server);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    Optional<SpawnPoint> point = rows.getObject("target_x") == null ? Optional.empty()
                            : Optional.of(new SpawnPoint(rows.getDouble("target_x"), rows.getDouble("target_y"),
                            rows.getDouble("target_z"), rows.getFloat("target_yaw"), rows.getFloat("target_pitch")));
                    ticket = new TravelTicket(UUID.fromString(rows.getString("id")), player,
                            rows.getString("portal"), rows.getString("target_world"), point);
                }
            }
            try (PreparedStatement consume = connection.prepareStatement(
                    "UPDATE atlas_travel_ticket SET consumed_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND consumed_at IS NULL")) {
                consume.setString(1, ticket.id().toString());
                return consume.executeUpdate() == 1 ? Optional.of(ticket) : Optional.empty();
            }
        }
    }

    /** Removes expired and consumed tickets older than a day. */
    public int purgeTickets() throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM atlas_travel_ticket WHERE origin_region = ? AND expires_at < CURRENT_TIMESTAMP(3) - INTERVAL 1 DAY")) {
            delete.setString(1, server);
            return delete.executeUpdate();
        }
    }

    /** The region code this repository writes, for messages and tests. */
    public String server() { return server.toUpperCase(Locale.ROOT); }
}
