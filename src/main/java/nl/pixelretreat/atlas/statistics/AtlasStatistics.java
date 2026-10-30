package nl.pixelretreat.atlas.statistics;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.logbook.client.DiagnosticBuffer;
import nl.pixelretreat.logbook.client.Fact;
import nl.pixelretreat.logbook.client.FactIdentity;
import nl.pixelretreat.logbook.client.MetricDefinition;
import nl.pixelretreat.logbook.client.ProducerLimits;
import nl.pixelretreat.logbook.client.ProducerWriter;

/** Source-owned Atlas facts. Durable calls use the caller's original source transaction. */
public final class AtlasStatistics {
    public static final String PORTAL_ATTEMPTS = "atlas:portal_attempts";
    public static final String PORTAL_ROUTES = "atlas:portal_routes";
    public static final String PORTAL_ARRIVAL_OBSERVATIONS = "atlas:portal_arrival_observations";
    public static final String PORTAL_DURATION = "atlas:portal_duration_ms";
    public static final String VISIT_DURATION = "atlas:world_visit_duration_ms";
    public static final String WORLD_OPERATIONS = "atlas:world_operations";
    public static final String WORLD_OPERATION_DURATION = "atlas:world_operation_duration_ms";
    public static final String KEEP_LOADED_REGIONS = "atlas:current_keep_loaded_regions";
    public static final String KEEP_LOADED_TICKETS = "atlas:current_keep_loaded_tickets";
    public static final String ADMIN_ACTIONS = "atlas:admin_actions";
    public static final String GAPS = "atlas:collection_gaps";
    private static final String WORLD_ORIGINS = "atlas_logbook_world_operations";
    private static final String PORTAL_ORIGINS = "atlas_logbook_portal_trips";
    private static final String VISITS = "atlas_logbook_world_visits";

    private final DataSource database;
    private final ProducerWriter writer;
    private final Clock clock;
    private final String backend;
    private final List<MetricDefinition> durableDefinitions = List.of(
            counter(PORTAL_ROUTES, "routes", "portal", "phase", "outcome", "origin_world", "target_world",
                    "origin_backend", "target_backend"),
            counter(PORTAL_ARRIVAL_OBSERVATIONS, "arrival_observations", "portal", "outcome", "target_world", "origin_backend"),
            distribution(PORTAL_DURATION, "milliseconds", "portal", "origin_world", "target_world",
                    "origin_backend", "target_backend"),
            distribution(VISIT_DURATION, "milliseconds", "world", "start_reason", "end_reason", "origin_backend"),
            counter(WORLD_OPERATIONS, "operations", "kind", "world", "phase", "outcome", "origin_backend"),
            distribution(WORLD_OPERATION_DURATION, "milliseconds", "kind", "world", "outcome", "origin_backend"),
            snapshot(KEEP_LOADED_REGIONS, "regions"),
            snapshot(KEEP_LOADED_TICKETS, "chunk_tickets"),
            counter(GAPS, "gaps", "cause", "source", "kind", "origin_backend"));
    private final MetricDefinition portalAttempts = counter(PORTAL_ATTEMPTS, "attempts",
            "portal", "outcome", "reason", "utc_day");
    private final MetricDefinition adminActions = counter(ADMIN_ACTIONS, "actions",
            "action", "outcome", "utc_day");
    private final MetricDefinition observationGaps = counter("atlas:observation_gaps", "gaps",
            "cause", "source", "kind", "utc_day");
    private final MetricDefinition diagnosticGaps = counter("atlas:diagnostic_gaps", "gaps", "reason");
    private final DiagnosticBuffer diagnostics;
    private final int captureCapacity;
    private final java.util.concurrent.atomic.AtomicInteger captured = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean captureClosed = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ConcurrentHashMap<UUID, UUID> observedVisits = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object snapshotLock = new Object();
    private java.util.concurrent.CompletableFuture<Void> snapshotTail = java.util.concurrent.CompletableFuture.completedFuture(null);
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.CompletableFuture<Void>> visitTails =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AtlasStatistics(DataSource database, ProducerLimits limits, String backend, int sourceQueueCapacity,
                           Runnable warning) {
        this(database, limits, backend, sourceQueueCapacity, warning, Clock.systemUTC());
    }

    AtlasStatistics(DataSource database, ProducerLimits limits, String backend, int sourceQueueCapacity,
                    Runnable warning, Clock clock) {
        this.database = java.util.Objects.requireNonNull(database);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.backend = checkedBackend(backend);
        if (sourceQueueCapacity < 1) throw new IllegalArgumentException("Invalid existing Atlas worker queue size");
        captureCapacity = sourceQueueCapacity;
        writer = new ProducerWriter(database, "atlas", limits.maximumPending(), limits.maximumFactBytes());
        diagnostics = new DiagnosticBuffer(database, "atlas", limits, this.backend, sourceQueueCapacity, 100,
                Set.of(portalAttempts, adminActions, observationGaps), diagnosticGaps, warning);
    }

    private WorldVisits visitsOnly;
    private final Set<UUID> retainedPortals = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Long> retainedWorlds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean retiredVisitsDrained;
    public boolean needsRetiredVisitRecovery() { return visitsOnly != null && !retiredVisitsDrained; }

    /** Select the approved visit-only policy before initialization; the old source remains for admitted recovery. */
    public void worldVisitsOnly(WorldVisits visits) { visitsOnly = java.util.Objects.requireNonNull(visits); }
    /** This non-live statistics round starts at the new build; no historical capture is reconciled. */
    public void freshWorldVisits(WorldVisits visits) { worldVisitsOnly(visits); retiredVisitsDrained = true; }
    public boolean capturesPortalTrips() { return visitsOnly == null; }
    public boolean retainsPortal(UUID id) { return visitsOnly == null || retainedPortals.contains(id); }
    public String backend() { return backend; }
    public void lockSource(Connection connection) throws SQLException { if (visitsOnly == null) writer.lockSource(connection); }
    public void lockSourceForRecovery(Connection connection) throws SQLException { writer.lockSource(connection); }
    public void lockWorldSource(Connection connection, long id) throws SQLException {
        if (visitsOnly == null || retainedWorlds.contains(id)) writer.lockSource(connection);
    }
    public void lockPortalSource(Connection connection, UUID id) throws SQLException {
        if (retainsPortal(id)) writer.lockSource(connection);
    }
    /** Incoming tickets can have been admitted on an older peer; prove those roots before source-row locking. */
    public void lockPlayerPortalSource(Connection connection, UUID player) throws SQLException {
        if (visitsOnly == null) { writer.lockSource(connection); return; }
        boolean retained = false;
        try (var q = connection.prepareStatement("SELECT operation_id FROM " + PORTAL_ORIGINS
                + " WHERE player_id=? AND terminal_at IS NULL")) {
            q.setString(1, player.toString());
            try (var rows = q.executeQuery()) {
                while (rows.next()) { retainedPortals.add(UUID.fromString(rows.getString(1))); retained = true; }
            }
        }
        if (retained) writer.lockSource(connection);
    }

    public void initialize() throws SQLException {
        writer.initialize();
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + WORLD_ORIGINS + " ("
                    + "operation_id CHAR(36) NOT NULL PRIMARY KEY, source_id BIGINT NOT NULL UNIQUE, "
                    + "actor_id CHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL, world VARCHAR(100) NOT NULL, "
                    + "source_world VARCHAR(100) NULL, origin_backend VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, accepted_at BIGINT NOT NULL, "
                    + "terminal_at BIGINT NULL, terminal_outcome VARCHAR(24) NULL, unresolved BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "KEY atlas_logbook_world_open (source_id, terminal_at)"
                    + ") ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS " + PORTAL_ORIGINS + " ("
                    + "operation_id CHAR(36) NOT NULL PRIMARY KEY, player_id CHAR(36) NOT NULL, portal VARCHAR(48) NOT NULL, "
                    + "origin_backend VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, target_backend VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, source_world VARCHAR(100) NOT NULL, "
                    + "target_world VARCHAR(100) NOT NULL, accepted_at BIGINT NOT NULL, arrival_consumed BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "terminal_at BIGINT NULL, terminal_outcome VARCHAR(24) NULL, KEY atlas_logbook_portal_open (terminal_at, accepted_at)"
                    + ") ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS " + VISITS + " ("
                    + "operation_id CHAR(36) NOT NULL PRIMARY KEY, player_id CHAR(36) NOT NULL, world VARCHAR(100) NOT NULL, "
                    + "source_backend VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, start_reason VARCHAR(32) NOT NULL, started_at BIGINT NOT NULL, state VARCHAR(16) NOT NULL, "
                    + "ended_at BIGINT NULL, end_reason VARCHAR(24) NULL, KEY atlas_logbook_visit_open (source_backend, player_id, state)"
                    + ") ENGINE=InnoDB");
            nl.pixelretreat.atlas.storage.ServerIdentityColumns.widen(connection,WORLD_ORIGINS,"origin_backend");
            nl.pixelretreat.atlas.storage.ServerIdentityColumns.widen(connection,PORTAL_ORIGINS,"origin_backend","target_backend");
            nl.pixelretreat.atlas.storage.ServerIdentityColumns.widen(connection,VISITS,"source_backend");
        }
        for (MetricDefinition definition : durableDefinitions) writer.register(definition);
        diagnostics.initialize();
        if (visitsOnly != null) {
            try (var c = database.getConnection(); var q = c.createStatement()) {
                try (var rows = q.executeQuery("SELECT operation_id FROM " + PORTAL_ORIGINS + " WHERE terminal_at IS NULL")) {
                    while (rows.next()) retainedPortals.add(UUID.fromString(rows.getString(1)));
                }
                try (var rows = q.executeQuery("SELECT source_id FROM " + WORLD_ORIGINS + " WHERE terminal_at IS NULL")) {
                    while (rows.next()) retainedWorlds.add(rows.getLong(1));
                }
            }
        }
    }

    /** Begins facts for a newly committed pending operation using its real Atlas row ID and timestamp. */
    public UUID startWorldOperation(Connection connection, long sourceId, PendingOperation.Kind kind,
                                    String world, String sourceWorld, UUID actor) throws SQLException {
        if (visitsOnly != null) return null;
        UUID operation = UUID.randomUUID();
        long acceptedAt;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT requested_at FROM atlas_pending_operation WHERE id = ? AND server = ?")) {
            query.setLong(1, sourceId); query.setString(2, backend);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Atlas operation source row is missing");
                acceptedAt = row.getTimestamp(1).getTime();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + WORLD_ORIGINS
                + " (operation_id, source_id, actor_id, kind, world, source_world, origin_backend, accepted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, operation.toString()); insert.setLong(2, sourceId); insert.setString(3, actor.toString());
            insert.setString(4, kind.name().toLowerCase(Locale.ROOT)); insert.setString(5, world);
            insert.setString(6, sourceWorld); insert.setString(7, backend); insert.setLong(8, acceptedAt);
            insert.executeUpdate();
        }
        writer.append(connection, fact(WORLD_OPERATIONS, operation, "accepted", actor, acceptedAt, 1,
                dims("kind", kind.name().toLowerCase(Locale.ROOT), "world", world,
                        "phase", "accepted", "outcome", "queued",
                        "origin_backend", backend)));
        writer.reserve(connection, operation, eventId(operation, WORLD_OPERATIONS, "terminal", actor));
        writer.reserve(connection, operation, eventId(operation, WORLD_OPERATION_DURATION, "duration", actor));
        writer.reserve(connection, operation, eventId(operation, GAPS, "duration-gap", actor));
        writer.reserve(connection, operation, eventId(operation, GAPS, "unresolved-gap", actor));
        return operation;
    }

    /** Writes a source-proven terminal outcome; ambiguous folder recovery emits a gap and keeps future holds. */
    public void finishWorldOperation(Connection connection, long sourceId, String outcome) throws SQLException {
        if (visitsOnly != null && !retainedWorlds.contains(sourceId)) return;
        WorldOrigin origin = worldOrigin(connection, sourceId);
        if (origin == null) {
            // Recovery cannot reconstruct a pre-producer accepted time or action root.
            observeGap("source_backend_missing", "atlas_pending_operation", "world_operation", null);
            return;
        }
        if (origin.terminalAt() != null || origin.unresolved()) return;
        if ("unresolved".equals(outcome)) {
            appendGap(connection, origin.operation(), origin.actor(), "world_operation_unresolved",
                    "atlas_pending_operation", "world_operation", origin.backend(),
                    eventId(origin.operation(), GAPS, "unresolved-gap", origin.actor()));
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + WORLD_ORIGINS
                    + " SET unresolved = TRUE WHERE source_id = ? AND terminal_at IS NULL AND unresolved = FALSE")) {
                update.setLong(1, sourceId); update.executeUpdate();
            }
            return;
        }
        long endedAt;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT finished_at FROM atlas_pending_operation WHERE id = ? AND server = ?")) {
            query.setLong(1, sourceId); query.setString(2, backend);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getTimestamp(1) == null) throw new SQLException("Atlas terminal timestamp is missing");
                endedAt = row.getTimestamp(1).getTime();
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + WORLD_ORIGINS
                + " SET terminal_at = ?, terminal_outcome = ? WHERE source_id = ? AND terminal_at IS NULL AND unresolved = FALSE")) {
            update.setLong(1, endedAt); update.setString(2, checkedToken(outcome)); update.setLong(3, sourceId);
            if (update.executeUpdate() != 1) return;
        }
        writer.append(connection, fact(WORLD_OPERATIONS, origin.operation(), "terminal", origin.actor(), endedAt, 1,
                dims("kind", origin.kind(), "world", origin.world(), "phase", "terminal", "outcome", checkedToken(outcome),
                        "origin_backend", origin.backend())));
        UUID durationEvent = eventId(origin.operation(), WORLD_OPERATION_DURATION, "duration", origin.actor());
        UUID durationGap = eventId(origin.operation(), GAPS, "duration-gap", origin.actor());
        try {
            long elapsed = Math.subtractExact(endedAt, origin.acceptedAt());
            if (elapsed < 0) throw new ArithmeticException("reversed time");
            writer.append(connection, fact(WORLD_OPERATION_DURATION, origin.operation(), "duration", origin.actor(), endedAt,
                    elapsed, dims("kind", origin.kind(), "world", origin.world(), "outcome", checkedToken(outcome),
                            "origin_backend", origin.backend())));
            writer.release(connection, origin.operation(), durationGap);
        } catch (ArithmeticException invalid) {
            appendGap(connection, origin.operation(), origin.actor(), "duration_invalid", "atlas_pending_operation",
                    "world_operation", origin.backend(), durationGap);
            writer.release(connection, origin.operation(), durationEvent);
        }
        writer.release(connection, origin.operation(), eventId(origin.operation(), GAPS, "unresolved-gap", origin.actor()));
    }

    /** Opens actual BACKEND coverage after the Postbox local node is ready. */
    public void beginCollection() throws SQLException {
        if (visitsOnly != null) return;
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                writer.lockSource(connection);
                for (MetricDefinition definition : durableDefinitions)
                    writer.beginCollection(connection, definition, backend);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
        diagnostics.beginCollection();
    }

    /** Creates an original portal root and admits its mutually exclusive terminal states before travel begins. */
    public long startPortalTrip(Connection connection, UUID operation, UUID player, String portal,
                                String sourceWorld, String targetBackend, String targetWorld) throws SQLException {
        if (visitsOnly != null) return 0;
        String target = checkedBackend(targetBackend);
        long acceptedAt = databaseNow(connection);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + PORTAL_ORIGINS
                + " (operation_id, player_id, portal, origin_backend, target_backend, source_world, target_world, accepted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, operation.toString()); insert.setString(2, player.toString());
            insert.setString(3, portal); insert.setString(4, backend); insert.setString(5, target);
            insert.setString(6, sourceWorld); insert.setString(7, targetWorld); insert.setLong(8, acceptedAt);
            insert.executeUpdate();
        }
        writer.append(connection, fact(PORTAL_ROUTES, operation, "accepted", player, acceptedAt,
                1, dims("portal", portal, "phase", "accepted", "outcome", "accepted",
                        "origin_world", sourceWorld, "target_world", targetWorld,
                        "origin_backend", backend, "target_backend", target)));
        UUID terminal = eventId(operation, PORTAL_ROUTES, "terminal", player);
        UUID duration = eventId(operation, PORTAL_DURATION, "duration", player);
        UUID durationGap = eventId(operation, GAPS, "duration-gap", player);
        UUID unresolvedGap = eventId(operation, GAPS, "unresolved-gap", player);
        writer.reserve(connection, operation, terminal);
        writer.reserve(connection, operation, duration);
        writer.reserve(connection, operation, durationGap);
        writer.reserve(connection, operation, unresolvedGap);
        if (!backend.equals(target)) writer.reserve(connection, operation,
                eventId(operation, PORTAL_ROUTES, "arrival-ticket-consumed", player));
        return acceptedAt;
    }

    /** Logs actual ticket consumption on its destination backend in the original claim transaction. */
    public void portalTicketConsumed(Connection connection, UUID operation, UUID player) throws SQLException {
        if (!retainsPortal(operation)) return;
        PortalOrigin origin = portalOrigin(connection, operation, true);
        if (origin == null) {
            LegacyTicketOrigin legacy = legacyTicketOrigin(connection, operation);
            if (legacy == null) {
                observeGap("source_backend_missing", "atlas_travel_ticket", "portal_trip", player);
                return;
            }
            // This is a current delivery attempt on an old ticket. Reserve only its terminal
            // observation before the teleport can run; do not import the original route start.
            writer.reserve(connection, operation, eventId(operation, PORTAL_ARRIVAL_OBSERVATIONS,
                    "observed-terminal", legacy.player()));
            observeGap("accepted_time_missing", "atlas_travel_ticket", "portal_trip", legacy.player());
            return;
        }
        if (origin.originBackend().equals(backend)) return;
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + PORTAL_ORIGINS
                + " SET arrival_consumed = TRUE WHERE operation_id = ? AND arrival_consumed = FALSE")) {
            update.setString(1, operation.toString());
            if (update.executeUpdate() == 1) {
                writer.append(connection, fact(PORTAL_ROUTES, operation, "arrival-ticket-consumed", origin.player(),
                        databaseNow(connection), 1, dims("portal", origin.portal(), "phase", "arrival",
                                "outcome", "ticket-consumed", "origin_backend", origin.originBackend(),
                                "origin_world", origin.sourceWorld(), "target_world", origin.targetWorld(),
                                "target_backend", origin.targetBackend())));
            }
        }
    }

    /** A proven route terminal consumes its original holds; malformed elapsed time becomes a visible gap. */
    public void finishPortalTrip(Connection connection, UUID operation, String outcome) throws SQLException {
        if (!retainsPortal(operation)) return;
        String terminalOutcome = checkedOutcome(outcome);
        PortalOrigin origin = portalOrigin(connection, operation, true);
        if (origin == null) {
            appendLegacyArrivalObservation(connection, operation, terminalOutcome);
            return;
        }
        if (origin.terminalAt() != null) return;
        long endedAt = databaseNow(connection);
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + PORTAL_ORIGINS
                + " SET terminal_at = ?, terminal_outcome = ? WHERE operation_id = ? AND terminal_at IS NULL")) {
            update.setLong(1, endedAt); update.setString(2, terminalOutcome); update.setString(3, operation.toString());
            if (update.executeUpdate() != 1) return;
        }
        writer.append(connection, fact(PORTAL_ROUTES, operation, "terminal", origin.player(), endedAt, 1,
                dims("portal", origin.portal(), "phase", "terminal", "outcome", terminalOutcome,
                        "origin_world", origin.sourceWorld(), "target_world", origin.targetWorld(),
                        "origin_backend", origin.originBackend(), "target_backend", origin.targetBackend())));
        writeDurationOrGap(connection, operation, origin.player(), origin.acceptedAt(), endedAt,
                PORTAL_DURATION, "duration", origin.portal(), origin.sourceWorld(), origin.targetWorld(),
                origin.originBackend(), origin.targetBackend(),
                "portal-trip", "atlas_logbook_portal_trips", eventId(operation, GAPS, "duration-gap", origin.player()));
        writer.release(connection, operation, eventId(operation, GAPS, "unresolved-gap", origin.player()));
    }

    /** Marks an ambiguous portal effect without consuming terminal/duration admission. */
    public void unresolvedPortalTrip(Connection connection, UUID operation, String cause) throws SQLException {
        if (!retainsPortal(operation)) return;
        PortalOrigin origin = portalOrigin(connection, operation, true);
        if (origin == null) {
            LegacyTicketOrigin legacy = legacyTicketOrigin(connection, operation);
            observeGap(legacy == null ? "source_backend_missing" : "accepted_time_missing",
                    "atlas_travel_ticket", "portal_trip", legacy == null ? null : legacy.player());
            return;
        }
        appendGap(connection, operation, origin.player(), cause, "atlas_logbook_portal_trips", "portal-trip",
                origin.originBackend(), eventId(operation, GAPS, "unresolved-gap", origin.player()));
    }

    /** Starts an observed world interval. It measures from this proof point; it never invents a join session. */
    public void startVisit(UUID player, String world, String startReason) {
        if (visitsOnly != null) {
            if ("readiness_observation".equals(startReason)) visitsOnly.presence(player, world);
            else visitsOnly.joined(player, world);
            return;
        }
        if (captureClosed.get()) return;
        Instant observedAt = clock.instant();
        UUID observation = UUID.randomUUID();
        observedVisits.put(player, observation);
        enqueueVisit(player, () -> {
            try (Connection connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    writer.lockSource(connection);
                    markOpenVisitUnresolved(connection, player);
                    openVisit(connection, observation, player, world, checkedVisitStart(startReason), observedAt);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
            return null;
        });
    }

    /** Ends the previous interval and begins the destination interval atomically at a world change. */
    public void rotateVisit(UUID player, String world) {
        if (visitsOnly != null) { visitsOnly.changed(player, world); return; }
        if (captureClosed.get()) return;
        Instant observedAt = clock.instant();
        UUID observation = UUID.randomUUID();
        UUID previous = observedVisits.put(player, observation);
        enqueueVisit(player, () -> {
            try (Connection connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    writer.lockSource(connection);
                    endOpenVisit(connection, player, previous, "world_change", observedAt);
                    openVisit(connection, observation, player, world, "world_change", observedAt);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
            return null;
        });
    }

    public void endVisit(UUID player, String endReason) {
        if (visitsOnly != null) { visitsOnly.quit(player); return; }
        if (captureClosed.get()) return;
        Instant observedAt = clock.instant();
        UUID observation = observedVisits.remove(player);
        enqueueVisit(player, () -> {
            try (Connection connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    writer.lockSource(connection);
                    endOpenVisit(connection, player, observation, checkedEndReason(endReason), observedAt);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
            return null;
        });
    }

    /** Reports an accepted current observation for staff operations that have no durable timed source row. */
    public void observeAdmin(UUID actor, String action, String outcome) {
        if (visitsOnly != null) return;
        diagnostics.aggregate(adminActions, actor, BigInteger.ONE,
                dims("action", checkedToken(action), "outcome", checkedToken(outcome)));
        requestDiagnosticFlush();
    }

    public void observePortalAttempt(UUID actor, String portal, String outcome, String reason) {
        if (visitsOnly != null) return;
        diagnostics.aggregate(portalAttempts, actor, BigInteger.ONE,
                dims("portal", portal, "outcome", checkedToken(outcome), "reason", checkedToken(reason)));
        requestDiagnosticFlush();
    }

    public void observeGap(String cause, String source, String kind, UUID actor) {
        if (visitsOnly != null) return;
        diagnostics.aggregate(observationGaps, actor, BigInteger.ONE,
                dims("cause", checkedToken(cause), "source", checkedToken(source), "kind", checkedToken(kind)));
        requestDiagnosticFlush();
    }

    /** Current-state keep-loaded observation, after the existing Folia ticket reconciliation completed. */
    public void currentKeepLoadedSnapshot(int configuredRegions, int heldTickets) {
        if (visitsOnly != null) return;
        if (!capture("snapshot_capture_full", null)) return;
        synchronized (snapshotLock) {
            snapshotTail = snapshotTail.handle((ignored, failure) -> null).thenCompose(ignored -> sourceSubmitter.apply(() -> {
            UUID observation = UUID.randomUUID();
            try (Connection connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    writer.lockSource(connection);
                    long at = databaseNow(connection);
                    writer.append(connection, fact(KEEP_LOADED_REGIONS, observation, "current", null, at,
                            configuredRegions, Map.of()));
                    writer.append(connection, fact(KEEP_LOADED_TICKETS, observation, "current", null, at,
                            heldTickets, Map.of()));
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    rollback(connection, failure);
                    throw failure;
                }
            }
            return null;
        })).exceptionally(failure -> {
            diagnostics.aggregate(observationGaps, null, BigInteger.ONE,
                    dims("cause", "keep_loaded_snapshot_unavailable", "source", "atlas_ticket_reconcile", "kind", "snapshot"));
            requestDiagnosticFlush();
            return null;
            }).whenComplete((ignored, failure) -> captured.decrementAndGet());
        }
    }

    public void requestDiagnosticFlush() {
        if (visitsOnly != null) return;
        submitSource(() -> { diagnostics.flush(); return null; });
    }

    public void closeCapture() { captureClosed.set(true); observedVisits.clear(); diagnostics.closeCapture(); }

    private void openVisit(Connection connection, UUID operation, UUID player, String world, String startReason,
                           Instant observedAt) throws SQLException {
        long at = observedAt.toEpochMilli();
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + VISITS
                + " (operation_id, player_id, world, source_backend, start_reason, started_at, state) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'OPEN')")) {
            insert.setString(1, operation.toString()); insert.setString(2, player.toString());
            insert.setString(3, world); insert.setString(4, backend); insert.setString(5, startReason);
            insert.setLong(6, at); insert.executeUpdate();
        }
        writer.reserve(connection, operation, eventId(operation, VISIT_DURATION, "duration", player));
        writer.reserve(connection, operation, eventId(operation, GAPS, "interrupted-gap", player));
    }

    private void endOpenVisit(Connection connection, UUID player, UUID expectedOperation, String reason,
                              Instant observedAt) throws SQLException {
        if (expectedOperation == null) return;
        try (PreparedStatement query = connection.prepareStatement("SELECT operation_id, world, source_backend, started_at, start_reason "
                + "FROM " + VISITS + " WHERE player_id = ? AND source_backend = ? AND operation_id = ? AND state = 'OPEN' FOR UPDATE")) {
            query.setString(1, player.toString()); query.setString(2, backend);
            query.setString(3, expectedOperation.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return;
                UUID operation = UUID.fromString(row.getString(1));
                String world = row.getString(2); String origin = row.getString(3); long startedAt = row.getLong(4);
                String startReason = row.getString(5);
                long endedAt = observedAt.toEpochMilli();
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + VISITS
                        + " SET state = 'ENDED', ended_at = ?, end_reason = ? WHERE operation_id = ? AND state = 'OPEN'")) {
                    update.setLong(1, endedAt); update.setString(2, reason); update.setString(3, operation.toString());
                    if (update.executeUpdate() != 1) return;
                }
                UUID durationEvent = eventId(operation, VISIT_DURATION, "duration", player);
                UUID gapEvent = eventId(operation, GAPS, "interrupted-gap", player);
                try {
                    long elapsed = Math.subtractExact(endedAt, startedAt);
                    if (elapsed < 0) throw new ArithmeticException("reversed time");
                    writer.append(connection, fact(VISIT_DURATION, operation, "duration", player, endedAt, elapsed,
                            dims("world", world, "start_reason", startReason, "end_reason", reason, "origin_backend", origin)));
                    writer.release(connection, operation, gapEvent);
                } catch (ArithmeticException invalid) {
                    appendGap(connection, operation, player, "duration_invalid", "atlas_logbook_world_visits",
                            "world_visit", origin, gapEvent);
                    writer.release(connection, operation, durationEvent);
                }
            }
        }
    }

    /** Small idle-worker retirement batch; original gap identity is consumed, unknown duration remains held. */
    public void reconcileRetiredVisits() throws SQLException {
        if (visitsOnly == null || captureClosed.get() || retiredVisitsDrained) return;
        try (var c = WorldVisits.bounded(database).getConnection()) {
            c.setAutoCommit(false);
            try {
                writer.lockSource(c);
                var players = new java.util.ArrayList<UUID>();
                try (var q = c.prepareStatement("SELECT DISTINCT player_id FROM " + VISITS
                        + " WHERE source_backend=? AND state='OPEN' LIMIT 16")) {
                    q.setString(1, backend); try (var rows=q.executeQuery()) { while(rows.next()) players.add(UUID.fromString(rows.getString(1))); }
                }
                for (var player : players) markOpenVisitUnresolved(c,player,"visit_capture_retired");
                c.commit();
                if (players.size() < 16) retiredVisitsDrained = true;
            } catch (SQLException | RuntimeException error) { c.rollback(); throw error; }
        }
    }
    private void markOpenVisitUnresolved(Connection connection, UUID player) throws SQLException {
        markOpenVisitUnresolved(connection,player,"visit_interrupted_by_restart");
    }
    private void markOpenVisitUnresolved(Connection connection, UUID player, String cause) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT operation_id, source_backend FROM " + VISITS
                + " WHERE player_id = ? AND source_backend = ? AND state = 'OPEN' FOR UPDATE")) {
            query.setString(1, player.toString()); query.setString(2, backend);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    UUID operation = UUID.fromString(row.getString(1)); String origin = row.getString(2);
                    appendGap(connection, operation, player, cause, "atlas_logbook_world_visits",
                            "world_visit", origin, eventId(operation, GAPS, "interrupted-gap", player));
                    try (PreparedStatement update = connection.prepareStatement("UPDATE " + VISITS
                            + " SET state = 'UNRESOLVED' WHERE operation_id = ? AND state = 'OPEN'")) {
                        update.setString(1, operation.toString()); update.executeUpdate();
                    }
                }
            }
        }
    }

    private void writeDurationOrGap(Connection connection, UUID operation, UUID actor, long startedAt, long endedAt,
                                    String metric, String stage, String feature, String sourceWorld, String targetWorld,
                                    String origin, String target,
                                    String kind, String source, UUID gapEvent) throws SQLException {
        UUID durationEvent = eventId(operation, metric, stage, actor);
        try {
            long elapsed = Math.subtractExact(endedAt, startedAt);
            if (elapsed < 0) throw new ArithmeticException("reversed time");
            writer.append(connection, fact(metric, operation, stage, actor, endedAt, elapsed,
                    dims("portal", feature, "origin_world", sourceWorld, "target_world", targetWorld,
                            "origin_backend", origin, "target_backend", target)));
            writer.release(connection, operation, gapEvent);
        } catch (ArithmeticException invalid) {
            appendGap(connection, operation, actor, "duration_invalid", source, kind, origin, gapEvent);
            writer.release(connection, operation, durationEvent);
        }
    }

    private void appendGap(Connection connection, UUID operation, UUID actor, String cause, String source,
                           String kind, String originBackend, UUID reservedEvent) throws SQLException {
        writer.append(connection, new Fact(reservedEvent, operation, GAPS, 1, actor, null,
                Instant.ofEpochMilli(databaseNow(connection)), backend, BigInteger.ONE,
                dims("cause", checkedToken(cause), "source", checkedToken(source), "kind", checkedToken(kind),
                        "origin_backend", checkedBackend(originBackend))));
    }

    private PortalOrigin portalOrigin(Connection connection, UUID operation, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT player_id, portal, origin_backend, target_backend, "
                + "source_world, target_world, accepted_at, terminal_at FROM " + PORTAL_ORIGINS + " WHERE operation_id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, operation.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                String sourceWorld = row.getString(5); String targetWorld = row.getString(6);
                long terminal = row.getLong(8); Long terminalAt = row.wasNull() ? null : terminal;
                return new PortalOrigin(UUID.fromString(row.getString(1)), row.getString(2), row.getString(3),
                        row.getString(4), sourceWorld, targetWorld, row.getLong(7), terminalAt);
            }
        }
    }

    /** Current terminal observation for a pre-producer ticket; this is not an imported route history. */
    private void appendLegacyArrivalObservation(Connection connection, UUID operation, String outcome) throws SQLException {
        LegacyTicketOrigin legacy = legacyTicketOrigin(connection, operation);
        if (legacy == null) {
            observeGap("source_backend_missing", "atlas_travel_ticket", "portal_trip", null);
            return;
        }
        writer.append(connection, fact(PORTAL_ARRIVAL_OBSERVATIONS, operation, "observed-terminal", legacy.player(),
                databaseNow(connection), 1,
                dims("portal", legacy.portal(), "outcome", checkedOutcome(outcome), "target_world", legacy.targetWorld(),
                        "origin_backend", legacy.originBackend())));
        observeGap("accepted_time_missing", "atlas_travel_ticket", "portal_trip", legacy.player());
    }

    private LegacyTicketOrigin legacyTicketOrigin(Connection connection, UUID operation) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player, portal, origin_region, target_world FROM atlas_travel_ticket WHERE id = ?")) {
            query.setString(1, operation.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                return new LegacyTicketOrigin(UUID.fromString(row.getString(1)), row.getString(2),
                        checkedBackend(row.getString(3)), row.getString(4));
            }
        }
    }

    private WorldOrigin worldOrigin(Connection connection, long sourceId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT operation_id, actor_id, kind, origin_backend, "
                + "world, accepted_at, terminal_at, unresolved FROM " + WORLD_ORIGINS + " WHERE source_id = ? FOR UPDATE")) {
            query.setLong(1, sourceId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                long terminal = row.getLong(7); Long terminalAt = row.wasNull() ? null : terminal;
                return new WorldOrigin(UUID.fromString(row.getString(1)), UUID.fromString(row.getString(2)),
                        row.getString(3), row.getString(4), row.getString(5), row.getLong(6), terminalAt, row.getBoolean(8));
            }
        }
    }

    private long databaseNow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)")) {
            if (!rows.next()) throw new SQLException("Atlas database clock unavailable");
            return rows.getLong(1);
        }
    }

    private Fact fact(String metric, UUID operation, String stage, UUID subject, long at, long value,
                      Map<String, String> dimensions) {
        return new Fact(eventId(operation, metric, stage, subject), operation, metric, 1, subject, null,
                Instant.ofEpochMilli(at), backend, BigInteger.valueOf(value), dimensions);
    }

    private static UUID eventId(UUID operation, String metric, String stage, UUID subject) {
        return FactIdentity.of(metric, operation, "atlas/" + metric + "/" + stage, subject);
    }

    private static MetricDefinition counter(String id, String unit, String... dimensions) {
        return definition(id, MetricDefinition.Kind.COUNTER, unit, dimensions);
    }
    private static MetricDefinition distribution(String id, String unit, String... dimensions) {
        return definition(id, MetricDefinition.Kind.DISTRIBUTION, unit, dimensions);
    }
    private static MetricDefinition snapshot(String id, String unit, String... dimensions) {
        return definition(id, MetricDefinition.Kind.SNAPSHOT, unit, dimensions);
    }
    private static MetricDefinition definition(String id, MetricDefinition.Kind kind, String unit, String... dimensions) {
        return new MetricDefinition(id, 1, kind, MetricDefinition.Scope.BACKEND, unit, List.of(dimensions));
    }
    private static Map<String, String> dims(String... pairs) {
        if ((pairs.length & 1) != 0) throw new IllegalArgumentException("Dimension pairs required");
        var result = new java.util.LinkedHashMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) result.put(pairs[index], pairs[index + 1]);
        return Map.copyOf(result);
    }
    private static String checkedBackend(String value) {
        if (value == null) throw new IllegalArgumentException("Actual Atlas backend is required");
        if (!nl.pixelretreat.atlas.config.AtlasConfig.validServerId(value)) throw new IllegalArgumentException("Invalid Atlas backend");
        return value;
    }
    private static String checkedOutcome(String outcome) {
        String value = checkedToken(outcome);
        if (!Set.of("arrived", "not_performed", "target_missing").contains(value))
            throw new IllegalArgumentException("Unsupported proven portal terminal");
        return value;
    }
    private static String checkedEndReason(String value) {
        String reason = checkedToken(value);
        if (!Set.of("quit", "world_change").contains(reason)) throw new IllegalArgumentException("Invalid visit end reason");
        return reason;
    }
    private static String checkedVisitStart(String value) {
        String reason = checkedToken(value);
        if (!Set.of("join", "readiness_observation", "world_change").contains(reason))
            throw new IllegalArgumentException("Invalid visit start reason");
        return reason;
    }
    private static String checkedToken(String value) {
        if (value == null || !value.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid Atlas metric value");
        return value;
    }
    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }

    private void submitSource(java.util.concurrent.Callable<Void> operation) {
        // The plugin assigns the existing bounded-worker submitter after startup; diagnostic capture remains no-IO.
        try {
            sourceSubmitter.apply(operation).whenComplete((ignored, failure) -> {
                if (failure != null) diagnostics.aggregate(observationGaps, null, BigInteger.ONE,
                        dims("cause", "worker_submission_unavailable", "source", "atlas_workers", "kind", "diagnostic_flush"));
            });
        } catch (RuntimeException rejected) {
            diagnostics.aggregate(observationGaps, null, BigInteger.ONE,
                    dims("cause", "worker_submission_unavailable", "source", "atlas_workers", "kind", "diagnostic_flush"));
        }
    }

    private void enqueueVisit(UUID player, java.util.concurrent.Callable<Void> operation) {
        if (!capture("visit_capture_full", player)) return;
        var scheduled = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        visitTails.compute(player, (ignored, previous) -> {
            var prior = previous == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null) : previous;
            var next = prior.handle((done, failure) -> null).thenCompose(done -> sourceSubmitter.apply(operation));
            scheduled.set(next);
            return next;
        });
        var next = scheduled.get();
        next.whenComplete((done, failure) -> {
            captured.decrementAndGet();
            if (failure != null) observeGap("visit_source_write_unavailable", "atlas_logbook_world_visits", "world_visit", player);
            visitTails.remove(player, next);
        });
    }

    private boolean capture(String cause, UUID player) {
        if (captureClosed.get()) return false;
        if (captured.incrementAndGet() <= captureCapacity) return true;
        captured.decrementAndGet();
        observeGap(cause, "atlas_workers", "observation", player);
        return false;
    }

    private volatile java.util.function.Function<java.util.concurrent.Callable<Void>, java.util.concurrent.CompletableFuture<Void>> sourceSubmitter =
            task -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Atlas worker not attached"));

    public void attachWorker(java.util.function.Function<java.util.concurrent.Callable<Void>, java.util.concurrent.CompletableFuture<Void>> submitter) {
        sourceSubmitter = java.util.Objects.requireNonNull(submitter);
    }

    private record PortalOrigin(UUID player, String portal, String originBackend, String targetBackend,
                                String sourceWorld, String targetWorld, long acceptedAt, Long terminalAt) { }
    private record LegacyTicketOrigin(UUID player, String portal, String originBackend, String targetWorld) { }
    private record WorldOrigin(UUID operation, UUID actor, String kind, String backend, String world, long acceptedAt,
                               Long terminalAt, boolean unresolved) { }
}
