package nl.pixelretreat.atlas.statistics;

import java.math.BigInteger;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sql.DataSource;
import nl.pixelretreat.logbook.client.*;

/** Optional world-use observations. Owner callbacks never perform I/O or wait for a worker. */
public final class WorldVisits {
    public static final MetricDefinition VISITS = new MetricDefinition("atlas:world_visits", 1,
        MetricDefinition.Kind.COUNTER, MetricDefinition.Scope.BACKEND, "visits", List.of("world", "reason"));
    public static final MetricDefinition WORLDS = new MetricDefinition("atlas:world_available", 1,
        MetricDefinition.Kind.SNAPSHOT, MetricDefinition.Scope.BACKEND, "worlds", List.of("world"));
    private static final int BATCH = 64, MAX_WORLDS = 256;
    private static final java.util.regex.Pattern WORLD_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private final DataSource database;
    private final ProducerWriter writer;
    private final String backend;
    private final UUID incarnation = UUID.randomUUID();
    private final Consumer<Throwable> warning;
    private final ArrayBlockingQueue<Fact> waiting;
    private final AtomicBoolean flushing = new AtomicBoolean(), warned = new AtomicBoolean();
    private final Object stateLock = new Object();
    private final Map<UUID, String> players = new HashMap<>();
    private final Map<String, Boolean> worlds = new HashMap<>();
    private final Map<String, Boolean> published = new HashMap<>();
    private final int capacity;
    private volatile boolean ready, closed;
    private boolean claimObserved;
    private String predecessor;
    private List<Fact> pending = List.of();
    private List<Fact> pendingWorlds = List.of();
    private Map<String, Boolean> pendingWorldState = Map.of();

    public WorldVisits(DataSource database, ProducerLimits limits, String backend, int capacity,
                       Consumer<Throwable> warning) {
        this.database = bounded(Objects.requireNonNull(database));
        if (!Set.of("EU", "NA").contains(backend) || capacity < 1)
            throw new IllegalArgumentException("Actual node and bounded capture capacity required");
        this.backend = backend; this.capacity = capacity; this.warning = Objects.requireNonNull(warning);
        writer = new ProducerWriter(this.database, "atlas", limits.maximumPending(), limits.maximumFactBytes());
        waiting = new ArrayBlockingQueue<>(capacity);
    }

    /** Existing IO worker only. Failed initialization leaves optional capture disabled. */
    public void initialize() throws SQLException {
        writer.initialize();
        try (var c = database.getConnection(); var q = c.createStatement()) {
            q.execute("CREATE TABLE IF NOT EXISTS atlas_logbook_visit_generation (backend VARCHAR(8) PRIMARY KEY,"
                + "incarnation CHAR(36) NOT NULL) ENGINE=InnoDB");
        }
        var events = new ObservationPolicy.Registration(VISITS, ObservationPolicy.Mode.PARTIAL_EVENTS,
            "atlas-world-use", 1, backend);
        var catalogue = new ObservationPolicy.Registration(WORLDS, ObservationPolicy.Mode.SAMPLED_SNAPSHOT,
            "atlas-world-use", 1, backend);
        try (var c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                writer.lockSource(c);
                if (closed) { c.rollback(); return; }
                String owner = null;
                try (var q = c.prepareStatement("SELECT incarnation FROM atlas_logbook_visit_generation WHERE backend=?")) {
                    q.setString(1, backend); try (var r = q.executeQuery()) { if (r.next()) owner = r.getString(1); }
                }
                if (!claimObserved) { predecessor = owner; claimObserved = true; }
                if (!incarnation.toString().equals(owner) && !Objects.equals(predecessor, owner)) {
                    c.rollback(); close(); return;
                }
                writer.register(c, VISITS); writer.register(c, WORLDS);
                ObservationPolicy.enroll(c, events); ObservationPolicy.enroll(c, catalogue);
                writer.beginCollection(c, VISITS, backend); writer.beginCollection(c, WORLDS, backend);
                try (var q = c.prepareStatement("INSERT INTO atlas_logbook_visit_generation VALUES(?,?) "
                        + "ON DUPLICATE KEY UPDATE incarnation=VALUES(incarnation)")) {
                    q.setString(1, backend); q.setString(2, incarnation.toString()); q.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
        }
        ready = !closed && ObservationPolicy.committed(database, events)
            && ObservationPolicy.committed(database, catalogue);
    }

    /** A readiness observation establishes presence, never a new join or invented visit. */
    public void presence(UUID player, String world) {
        remember(player, world, null);
    }
    public void joined(UUID player, String world) { remember(player, world, "join"); }
    public void changed(UUID player, String world) { remember(player, world, "world_change"); }

    private void remember(UUID player, String world, String reason) {
        if (closed) return;
        if (player == null || !checkedWorld(world)) return;
        synchronized (stateLock) {
            if (!players.containsKey(player) && players.size() >= capacity) return;
            String previous = players.put(player, world);
            if (Objects.equals(previous, world) || reason == null || !ready) return;
        }
        if (waiting.remainingCapacity() == 0) return;
        UUID operation = UUID.randomUUID();
        // Queue overflow drops only this observation; no callback, SQL, encoding or retry on the owner.
        waiting.offer(new Fact(FactIdentity.of(VISITS.id(), operation, reason, player), operation,
            VISITS.id(), 1, player, null, Instant.now(), backend, BigInteger.ONE,
            Map.of("world", world, "reason", reason)));
    }

    public void quit(UUID player) { synchronized (stateLock) { players.remove(player); } }

    /** Actual WorldLoad/WorldUnload state, with a hard bound; no world/chunk scan is scheduled. */
    public void available(String world, boolean loaded) {
        if (closed) return;
        if (!checkedWorld(world)) return;
        synchronized (stateLock) {
            if (!worlds.containsKey(world) && worlds.size() >= MAX_WORLDS) return;
            worlds.put(world, loaded);
        }
    }

    /** One scheduled existing worker, at most 64 visit facts plus 64 changed catalogue entries. */
    public void flush() {
        if (closed || !flushing.compareAndSet(false, true)) return;
        Map<String, Boolean> catalogue = new TreeMap<>();
        try {
            if (!ready) { initialize(); if (!ready) return; }
            if (pending.isEmpty()) {
                var batch = new ArrayList<Fact>(BATCH);
                waiting.drainTo(batch, BATCH); pending = List.copyOf(batch);
            }
            synchronized (stateLock) {
                for (var entry : worlds.entrySet()) {
                    if (!Objects.equals(published.get(entry.getKey()), entry.getValue()))
                        catalogue.put(entry.getKey(), entry.getValue());
                    if (catalogue.size() == BATCH) break;
                }
            }
            if (pending.isEmpty() && pendingWorlds.isEmpty() && catalogue.isEmpty()) return;
            try (var c = database.getConnection()) {
                c.setAutoCommit(false);
                try {
                    writer.lockSource(c);
                    if (closed) { c.rollback(); return; }
                    try (var q = c.prepareStatement("SELECT incarnation FROM atlas_logbook_visit_generation WHERE backend=?")) {
                        q.setString(1, backend);
                        try (var row = q.executeQuery()) {
                            if (!row.next() || !incarnation.toString().equals(row.getString(1))) {
                                c.rollback(); close(); return;
                            }
                        }
                    }
                    for (var fact : pending) writer.append(c, fact);
                    if (!pendingWorlds.isEmpty()) {
                        int committed = 0;
                        for (var sample : pendingWorlds) {
                            boolean exists = false;
                            try (var q = c.prepareStatement("SELECT body FROM logbook_outbox WHERE owner='atlas' AND event_id=? "
                                    + "UNION ALL SELECT body FROM logbook_events WHERE owner='atlas' AND event_id=?")) {
                                q.setString(1, sample.eventId().toString()); q.setString(2, sample.eventId().toString());
                                try (var rows = q.executeQuery()) {
                                    while (rows.next()) {
                                        if (!Arrays.equals(rows.getBytes(1), CanonicalCodec.encode(sample, 32768)))
                                            throw new FactConflictException();
                                        exists = true;
                                    }
                                }
                            }
                            if (exists) committed++;
                        }
                        if (committed != 0 && committed != pendingWorlds.size()) throw new FactConflictException();
                        if (committed > 0) synchronized (stateLock) { published.putAll(pendingWorldState); }
                        pendingWorlds = List.of(); pendingWorldState = Map.of();
                    }
                    // Recompute dirty samples from current canonical source state AFTER producer ordering.
                    catalogue.clear();
                    synchronized (stateLock) {
                        for (var entry : worlds.entrySet()) {
                            if (!Objects.equals(published.get(entry.getKey()), entry.getValue())) catalogue.put(entry.getKey(), entry.getValue());
                            if (catalogue.size() == BATCH) break;
                        }
                    }
                    if (pendingWorlds.isEmpty()) {
                        synchronized (stateLock) {
                            for (var world : catalogue.keySet()) catalogue.put(world, worlds.get(world));
                        }
                        var samples = new ArrayList<Fact>();
                        for (var entry : catalogue.entrySet()) {
                            UUID operation = UUID.randomUUID();
                            samples.add(new Fact(FactIdentity.of(WORLDS.id(), operation, "observed", null),
                                operation, WORLDS.id(), 1, null, null, Instant.now(), backend,
                                entry.getValue() ? BigInteger.ONE : BigInteger.ZERO, Map.of("world", entry.getKey())));
                        }
                        pendingWorlds = List.copyOf(samples); pendingWorldState = Map.copyOf(catalogue);
                    }
                    for (var fact : pendingWorlds) writer.append(c, fact);
                    c.commit();
                } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
            }
            pending = List.of();
            synchronized (stateLock) { published.putAll(pendingWorldState); }
            pendingWorlds = List.of(); pendingWorldState = Map.of();
        } catch (SQLException | RuntimeException error) {
            // Stable pending event IDs make even an uncertain commit replay-safe. Snapshot sampling stays partial.
            if (warned.compareAndSet(false, true)) warning.accept(error);
        } finally { flushing.set(false); }
    }

    public void close() {
        closed = true; ready = false; waiting.clear();
        synchronized (stateLock) { players.clear(); worlds.clear(); }
    }
    private static boolean checkedWorld(String world) {
        return world != null && world.length() <= 100 && WORLD_ID.matcher(world).matches();
    }

    static DataSource bounded(DataSource source) { return new ObservationSource(source); }
    private record ObservationSource(DataSource source) implements DataSource {
        @Override public Connection getConnection() throws SQLException { return wrap(source.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(source.getConnection(user,password)); }
        private Connection wrap(Connection connection) throws SQLException {
            QueryBudget budget;
            try { budget = new QueryBudget(connection); }
            catch (SQLException error) { try { connection.abort(Runnable::run); } catch (SQLException abort) { error.addSuppressed(abort); } connection.close(); throw error; }
            var ended = new AtomicBoolean();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) {
                        if (ended.compareAndSet(false,true)) { try { budget.close(); } finally { connection.close(); } }
                        return null;
                    }
                    try { return method.invoke(connection,arguments); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
        }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { return source.getLogWriter(); }
        @Override public void setLogWriter(java.io.PrintWriter writer) throws SQLException { source.setLogWriter(writer); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { source.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return source.getLoginTimeout(); }
        @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException { return source.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { return source.unwrap(type); }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return source.isWrapperFor(type); }
    }

    /** Restore pooled session state even on failure; no long lock/statement wait for optional observations. */
    static final class QueryBudget implements AutoCloseable {
        private final Connection connection;
        private final java.math.BigDecimal statementSeconds;
        private final long lockSeconds;
        private final int networkMillis;
        QueryBudget(Connection connection) throws SQLException {
            this.connection = connection;
            networkMillis = connection.getNetworkTimeout(); connection.setNetworkTimeout(Runnable::run,1000);
            try (var q = connection.createStatement(); var r = q.executeQuery("SELECT @@SESSION.max_statement_time,@@SESSION.innodb_lock_wait_timeout")) {
                if (!r.next()) throw new SQLException("Observation budget unavailable");
                statementSeconds = r.getBigDecimal(1); lockSeconds = r.getLong(2);
            }
            try (var q = connection.createStatement()) {
                q.execute("SET SESSION max_statement_time=1, innodb_lock_wait_timeout=1");
            }
        }
        @Override public void close() throws SQLException {
            try (var q = connection.prepareStatement("SET SESSION max_statement_time=?, innodb_lock_wait_timeout=?")) {
                q.setBigDecimal(1, statementSeconds); q.setLong(2, lockSeconds); q.execute();
                connection.setNetworkTimeout(Runnable::run,networkMillis);
            } catch (SQLException error) {
                try { connection.abort(Runnable::run); } catch (SQLException abort) { error.addSuppressed(abort); }
                throw error;
            }
        }
    }
}
