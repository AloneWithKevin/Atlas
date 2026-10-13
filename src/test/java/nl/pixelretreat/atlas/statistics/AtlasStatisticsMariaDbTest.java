package nl.pixelretreat.atlas.statistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.atlas.service.AtlasWorkers;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.logbook.client.CanonicalCodec;
import nl.pixelretreat.logbook.client.Fact;
import nl.pixelretreat.logbook.client.FactIdentity;
import nl.pixelretreat.logbook.client.ProducerLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Source transaction, admission and actual-backend coverage against the isolated MariaDB fixture. */
final class AtlasStatisticsMariaDbTest {
    private static final Pattern PRIVATE_FIXTURE_URL = Pattern.compile(
            "^jdbc:mariadb://127\\.0\\.0\\.1:(\\d+)/atlas_audit\\?sslMode=disable$");
    private static final Pattern OWNED_SCHEMA = Pattern.compile("atlas_audit_case_[0-9a-f]{32}");
    private String fixtureJdbcUrl;
    private String ownedSchema;
    private int fixturePort;
    private Path fixtureDataDirectory;
    private String fixtureUser;
    private String fixturePassword;

    /** Every SQL test owns a disposable schema on the exact shared private MariaDB fixture. */
    @BeforeEach void createPerTestSchema() throws Exception {
        String configured = System.getenv("ATLAS_TEST_JDBC_URL");
        if (configured == null || configured.isBlank()) return; // Test methods retain their explicit skip when SQL is not configured.
        Matcher url = PRIVATE_FIXTURE_URL.matcher(configured);
        if (!url.matches()) throw new IllegalStateException("ATLAS_TEST_JDBC_URL must target the guarded loopback atlas_audit fixture");
        fixturePort = Integer.parseInt(url.group(1));
        String sharedPort = System.getenv("LOGBOOK_TEST_DB_PORT");
        String sharedSchema = System.getenv("LOGBOOK_TEST_DB_SCHEMA");
        String dataDirectory = System.getenv("LOGBOOK_TEST_DB_DATADIR");
        if (!Integer.toString(fixturePort).equals(sharedPort)
                || !"logbook_website_dashboard_test".equals(sharedSchema)
                || dataDirectory == null || dataDirectory.isBlank())
            throw new IllegalStateException("Atlas SQL tests require the shared guarded private fixture identity");
        fixtureDataDirectory = Path.of(dataDirectory).toRealPath();
        fixtureUser = System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root");
        fixturePassword = System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", "");
        if (!"root".equals(fixtureUser)) throw new IllegalStateException("Private audit fixture must use its local root account");

        String adminUrl = "jdbc:mariadb://127.0.0.1:" + fixturePort + "/?sslMode=disable";
        try (Connection admin = DriverManager.getConnection(adminUrl, fixtureUser, fixturePassword)) {
            verifyPrivateFixture(admin);
            try (var query = admin.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='atlas_audit'")) {
                try (var rows = query.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException("Guarded base schema is missing");
                }
            }
            ownedSchema = "atlas_audit_case_" + UUID.randomUUID().toString().replace("-", "");
            if (!OWNED_SCHEMA.matcher(ownedSchema).matches()) throw new IllegalStateException("Invalid generated disposable schema name");
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + ownedSchema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            }
            fixtureJdbcUrl = "jdbc:mariadb://127.0.0.1:" + fixturePort + "/" + ownedSchema + "?sslMode=disable";
        }
    }

    @AfterEach void dropOnlyOwnedTestSchema() throws Exception {
        if (ownedSchema == null) return;
        if (!OWNED_SCHEMA.matcher(ownedSchema).matches()) throw new IllegalStateException("Refusing to drop an unowned schema name");
        String adminUrl = "jdbc:mariadb://127.0.0.1:" + fixturePort + "/?sslMode=disable";
        try (Connection admin = DriverManager.getConnection(adminUrl, fixtureUser, fixturePassword)) {
            verifyPrivateFixture(admin);
            try (var statement = admin.createStatement()) {
                statement.execute("DROP DATABASE `" + ownedSchema + "`");
            }
        } finally {
            ownedSchema = null;
        }
    }

    private void verifyPrivateFixture(Connection admin) throws SQLException {
        try (var statement = admin.createStatement(); var rows = statement.executeQuery("SELECT @@datadir, @@port, @@bind_address")) {
            if (!rows.next()) throw new SQLException("Private fixture identity is unavailable");
            Path actualDirectory;
            try { actualDirectory = Path.of(rows.getString(1)).toRealPath(); }
            catch (java.io.IOException failure) { throw new SQLException("Private fixture directory is unavailable", failure); }
            if (!fixtureDataDirectory.equals(actualDirectory) || rows.getInt(2) != fixturePort
                    || !"127.0.0.1".equals(rows.getString(3)))
                throw new SQLException("MariaDB no longer matches the owned private fixture; schema cleanup refused");
        }
    }
    @Test void durableOperationsPortalsVisitsAndSnapshotsUseOriginalProofAndFiniteHolds() throws Exception {
        String url = fixtureJdbcUrl;
        assumeTrue(url != null && !url.isBlank(), "Use an isolated ATLAS_TEST_JDBC_URL");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);

        try (HikariDataSource pool = new HikariDataSource(config);
             AtlasWorkers workers = new AtlasWorkers(3, 64)) {
            AtlasRepository eu = new AtlasRepository(pool, "EU");
            eu.initialize();
            assertThrows(IllegalArgumentException.class,
                    () -> new AtlasStatistics(pool, ProducerLimits.APPROVED_START, "eu", 64, () -> { }),
                    "backend aliases must not invent separate collection identities");
            AtlasStatistics euStats = statistics(pool, "EU", new ProducerLimits(5, 32768), workers);
            eu.statistics(euStats);

            UUID actor = UUID.randomUUID();
            String firstWorld = "atlas:logbook-" + UUID.randomUUID().toString().substring(0, 8);
            long first = eu.queue(PendingOperation.Kind.DELETE, firstWorld, Optional.empty(), actor);
            assertThrows(SQLException.class, () -> eu.queue(PendingOperation.Kind.DELETE,
                    "atlas:rejected-" + UUID.randomUUID().toString().substring(0, 8), Optional.empty(), actor),
                    "a full outbox refuses the source operation before it can commit");
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_pending_operation WHERE server='EU' AND world=?", firstWorld));
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_world_operations WHERE source_id=?", first));

            assertTrue(eu.finish(first, true, "done"));
            assertFalse(eu.finish(first, true, "duplicate"), "source retry cannot create another terminal fact");
            assertEquals(3, count(pool, "SELECT pending_count FROM logbook_producers WHERE owner='atlas'"));
            assertEquals(0, count(pool, "SELECT reserved_count FROM logbook_producers WHERE owner='atlas'"));
            assertEquals(2, count(pool, "SELECT COUNT(*) FROM logbook_admission WHERE owner='atlas' AND operation_id=(SELECT operation_id FROM atlas_logbook_world_operations WHERE source_id=?) AND state='CONSUMED'", first));
            assertEquals(2, count(pool, "SELECT COUNT(*) FROM logbook_admission WHERE owner='atlas' AND operation_id=(SELECT operation_id FROM atlas_logbook_world_operations WHERE source_id=?) AND state='RELEASED'", first));

            // Existing source rows without producer roots are observed only as a diagnostic gap.
            AtlasRepository legacy = new AtlasRepository(pool, "EU");
            String legacyWorld = "atlas:legacy-" + UUID.randomUUID().toString().substring(0, 8);
            long legacyOperation = legacy.queue(PendingOperation.Kind.DELETE, legacyWorld, Optional.empty(), actor);
            AtlasStatistics fullStats = statistics(pool, "EU", ProducerLimits.APPROVED_START, workers);
            eu.statistics(fullStats);
            assertTrue(eu.finish(legacyOperation, true, "recovered"));
            assertEquals(0, count(pool, "SELECT COUNT(*) FROM atlas_logbook_world_operations WHERE source_id=?", legacyOperation));
            assertTrue(awaitFacts(pool, facts -> facts.stream().anyMatch(fact ->
                    fact.metric().equals("atlas:observation_gaps")
                            && "source_backend_missing".equals(fact.dimensions().get("cause"))), 5));

            String invalidWorld = "atlas:invalid-time-" + UUID.randomUUID().toString().substring(0, 8);
            long invalid = eu.queue(PendingOperation.Kind.DELETE, invalidWorld, Optional.empty(), actor);
            UUID invalidRoot = UUID.fromString(string(pool,
                    "SELECT operation_id FROM atlas_logbook_world_operations WHERE source_id=?", invalid));
            try (Connection connection = pool.getConnection(); PreparedStatement update = connection.prepareStatement(
                    "UPDATE atlas_logbook_world_operations SET accepted_at=? WHERE operation_id=?")) {
                update.setLong(1, Long.MAX_VALUE);
                update.setString(2, invalidRoot.toString());
                assertEquals(1, update.executeUpdate());
            }
            assertTrue(eu.finish(invalid, true, "done"));
            UUID invalidDuration = event("atlas:world_operation_duration_ms", invalidRoot, "duration", actor);
            UUID invalidGap = event("atlas:collection_gaps", invalidRoot, "duration-gap", actor);
            assertFalse(outboxContains(pool, invalidDuration), "invalid elapsed time never becomes a fabricated zero duration");
            assertTrue(outboxContains(pool, invalidGap), "the original reserved terminal slot carries the duration gap");

            UUID player = UUID.randomUUID();
            TravelTicket ticket = new TravelTicket(UUID.randomUUID(), player, "gate", "atlas:destination", Optional.empty());
            eu.issueTicket(ticket, "NA", Instant.now().plusSeconds(60), "minecraft:overworld");
            AtlasRepository na = new AtlasRepository(pool, "NA");
            AtlasStatistics naStats = statistics(pool, "NA", ProducerLimits.APPROVED_START, workers);
            na.statistics(naStats);
            assertEquals(ticket, na.takeTicket(player).orElseThrow());
            na.finishPortalTrip(ticket.id(), "arrived");
            assertTrue(outboxContains(pool, event("atlas:portal_routes", ticket.id(), "accepted", player)));
            assertTrue(outboxContains(pool, event("atlas:portal_routes", ticket.id(), "arrival-ticket-consumed", player)));
            assertTrue(outboxContains(pool, event("atlas:portal_routes", ticket.id(), "terminal", player)));
            assertTrue(outboxContains(pool, event("atlas:portal_duration_ms", ticket.id(), "duration", player)));

            UUID legacyPlayer = UUID.randomUUID();
            TravelTicket legacyTicket = new TravelTicket(UUID.randomUUID(), legacyPlayer, "old_gate",
                    "atlas:destination", Optional.empty());
            legacy.issueTicket(legacyTicket, "NA", Instant.now().plusSeconds(60));
            assertEquals(legacyTicket, na.takeTicket(legacyPlayer).orElseThrow());
            na.finishPortalTrip(legacyTicket.id(), "arrived");
            assertTrue(outboxContains(pool, event("atlas:portal_arrival_observations", legacyTicket.id(),
                    "observed-terminal", legacyPlayer)), "legacy delivery is measured only as the current observed effect");
            assertFalse(outboxContains(pool, event("atlas:portal_routes", legacyTicket.id(), "accepted", legacyPlayer)),
                    "legacy route starts are never imported");
            assertFalse(outboxContains(pool, event("atlas:portal_duration_ms", legacyTicket.id(), "duration", legacyPlayer)),
                    "legacy route duration has no original accepted-time proof");
            assertTrue(awaitFacts(pool, facts -> facts.stream().anyMatch(fact ->
                    fact.metric().equals("atlas:observation_gaps")
                            && "accepted_time_missing".equals(fact.dimensions().get("cause"))), 5));

            UUID visitPlayer = UUID.randomUUID();
            naStats.startVisit(visitPlayer, "minecraft:overworld", "join");
            assertTrue(awaitQuery(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND state='OPEN'",
                    visitPlayer.toString(), value -> value == 1, 5));
            naStats.rotateVisit(visitPlayer, "atlas:destination");
            assertTrue(awaitQuery(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND state='OPEN'",
                    visitPlayer.toString(), value -> value == 1, 5));
            naStats.endVisit(visitPlayer, "quit");
            assertTrue(awaitQuery(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND state='ENDED'",
                    visitPlayer.toString(), value -> value == 2, 5));
            assertEquals(2, facts(pool).stream().filter(fact -> fact.metric().equals("atlas:world_visit_duration_ms")
                    && visitPlayer.equals(fact.player())).count());

            UUID delayedPlayer = UUID.randomUUID();
            Instant observedStart = Instant.parse("2026-10-13T10:00:00.123Z");
            Instant observedEnd = observedStart.plusMillis(4_200);
            MutableClock eventClock = new MutableClock(observedStart);
            try (AtlasWorkers queuedWorkers = new AtlasWorkers(1, 16)) {
                AtlasStatistics delayedStats = statistics(pool, "NA", ProducerLimits.APPROVED_START, queuedWorkers, eventClock);
                CountDownLatch workerEntered = new CountDownLatch(1);
                CountDownLatch releaseWorker = new CountDownLatch(1);
                var blocker = queuedWorkers.submit(() -> {
                    workerEntered.countDown();
                    if (!releaseWorker.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker gate timed out");
                    return null;
                });
                try {
                    assertTrue(workerEntered.await(5, TimeUnit.SECONDS));
                    delayedStats.startVisit(delayedPlayer, "minecraft:overworld", "join");
                    eventClock.set(observedEnd);
                    delayedStats.endVisit(delayedPlayer, "quit");
                    TimeUnit.MILLISECONDS.sleep(250); // Deliberately hold both captures behind the worker queue.
                    releaseWorker.countDown();
                    blocker.get(5, TimeUnit.SECONDS);
                    assertTrue(awaitQuery(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND state='ENDED'",
                            delayedPlayer.toString(), value -> value == 1, 5));
                    Fact duration = facts(pool).stream().filter(fact -> fact.metric().equals("atlas:world_visit_duration_ms")
                            && delayedPlayer.equals(fact.player())).findFirst().orElseThrow();
                    assertEquals(BigInteger.valueOf(4_200), duration.value(), "worker queue delay must not extend the measured visit");
                    assertEquals(observedEnd.toEpochMilli(), duration.occurredAt().toEpochMilli());
                    assertEquals(Long.toString(observedStart.toEpochMilli()), string(pool,
                            "SELECT started_at FROM atlas_logbook_world_visits WHERE player_id=?", delayedPlayer.toString()));
                    assertEquals(Long.toString(observedEnd.toEpochMilli()), string(pool,
                            "SELECT ended_at FROM atlas_logbook_world_visits WHERE player_id=?", delayedPlayer.toString()));
                    assertEquals(duration.operationId().toString(), string(pool,
                            "SELECT operation_id FROM atlas_logbook_world_visits WHERE player_id=?", delayedPlayer.toString()),
                            "the end capture must retain the expected original visit operation ID");
                } finally {
                    releaseWorker.countDown(); delayedStats.closeCapture();
                }
            }

            naStats.currentKeepLoadedSnapshot(2, 3);
            assertTrue(awaitFacts(pool, facts -> facts.stream().anyMatch(fact ->
                    fact.metric().equals("atlas:current_keep_loaded_tickets") && fact.value().equals(BigInteger.valueOf(3))), 5));
            assertEquals(13, count(pool, "SELECT COUNT(*) FROM logbook_backend_coverage WHERE owner='atlas' AND backend='EU'"));
            assertEquals(13, count(pool, "SELECT COUNT(*) FROM logbook_backend_coverage WHERE owner='atlas' AND backend='NA'"));

            UUID interruptedPlayer = UUID.randomUUID();
            naStats.startVisit(interruptedPlayer, "minecraft:overworld", "join");
            assertTrue(awaitQuery(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND state='OPEN'",
                    interruptedPlayer.toString(), value -> value == 1, 5));
            AtlasStatistics replacement = statistics(pool, "NA", ProducerLimits.APPROVED_START, workers);
            replacement.endVisit(interruptedPlayer, "quit");
            replacement.rotateVisit(interruptedPlayer, "atlas:new-observation");
            assertTrue(awaitQuery(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND world='atlas:new-observation' AND state='OPEN'",
                    interruptedPlayer.toString(), value -> value == 1, 5));
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_world_visits WHERE player_id=? AND world='minecraft:overworld' AND state='OPEN'", interruptedPlayer),
                    "a replacement observer cannot close an interval it never observed");

            String naOperationWorld = "atlas:na-duration-" + UUID.randomUUID().toString().substring(0, 8);
            long naOperation = na.queue(PendingOperation.Kind.DELETE, naOperationWorld, Optional.empty(), actor);
            assertTrue(na.finish(naOperation, true, "deleted"));
            // Original onLoad folder work finishes before producer wiring; recover its exact terminal once.
            String startupWorld = "atlas:startup-" + UUID.randomUUID().toString().substring(0, 8);
            long startup = eu.queue(PendingOperation.Kind.DELETE, startupWorld, Optional.empty(), actor);
            AtlasRepository startupRepository = new AtlasRepository(pool, "EU");
            assertTrue(startupRepository.finish(startup, true, "deleted"));
            assertEquals(0, count(pool, "SELECT COUNT(*) FROM atlas_logbook_world_operations WHERE source_id=? AND terminal_at IS NOT NULL", startup));
            eu.reconcileWorldStatistics(); eu.reconcileWorldStatistics();
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_world_operations WHERE source_id=? AND terminal_at IS NOT NULL", startup));

            UUID firstRoot = UUID.fromString(string(pool,
                    "SELECT operation_id FROM atlas_logbook_world_operations WHERE source_id=?", first));
            UUID naRoot = UUID.fromString(string(pool,
                    "SELECT operation_id FROM atlas_logbook_world_operations WHERE source_id=?", naOperation));
            UUID startupRoot = UUID.fromString(string(pool,
                    "SELECT operation_id FROM atlas_logbook_world_operations WHERE source_id=?", startup));
            var measuredRoots = java.util.Set.of(firstRoot, naRoot, startupRoot);
            var sourceOperationDurations = facts(pool).stream()
                    .filter(fact -> fact.metric().equals("atlas:world_operation_duration_ms")
                            && measuredRoots.contains(fact.operationId())).toList();
            var euDurations = sourceOperationDurations.stream().filter(fact -> "EU".equals(fact.backend())).toList();
            var naDurations = sourceOperationDurations.stream().filter(fact -> "NA".equals(fact.backend())).toList();
            assertEquals(2, euDurations.size(), "two proved original EU operations have duration samples");
            assertEquals(1, naDurations.size(), "one proved original NA operation has a duration sample");
            var expectedEuSum = euDurations.stream().map(Fact::value).reduce(BigInteger.ZERO, BigInteger::add);
            var expectedNaSum = naDurations.stream().map(Fact::value).reduce(BigInteger.ZERO, BigInteger::add);
            var expectedNetworkSum = expectedEuSum.add(expectedNaSum);
            var expectedEuCount = BigInteger.valueOf(euDurations.size());
            var expectedNaCount = BigInteger.valueOf(naDurations.size());

            try (var connection = pool.getConnection()) {
                nl.pixelretreat.logbook.core.ProjectionSchema.initialize(connection);
            }
            var collector = new nl.pixelretreat.logbook.core.IngestRepository(pool);
            for (int batch = 0; batch < 100 && count(pool, "SELECT pending_count FROM logbook_producers WHERE owner='atlas'") > 0; batch++) {
                collector.drain("atlas", 100);
            }
            assertEquals(0, count(pool, "SELECT pending_count FROM logbook_producers WHERE owner='atlas'"));
            var query = new nl.pixelretreat.logbook.api.StatisticsQuery("atlas:portal_routes", 1, Optional.empty(),
                    Optional.empty(), Optional.empty(), java.util.Map.of("phase", "terminal", "outcome", "arrived"));
            var absent = new nl.pixelretreat.logbook.api.StatisticsQuery("atlas:unmeasured", 1, Optional.empty(),
                    Optional.empty(), Optional.empty(), java.util.Map.of());
            var reads = new nl.pixelretreat.logbook.core.ReadRepository(pool, new nl.pixelretreat.logbook.core.ReadLimits(100, 100, 100, 30));
            var values = reads.read(java.util.Set.of(query, absent));
            assertEquals(new java.math.BigDecimal("1"), values.get(query).value().orElseThrow());
            assertEquals(nl.pixelretreat.logbook.api.StatisticValue.Status.UNKNOWN, values.get(absent).status());
            var durationEu = new nl.pixelretreat.logbook.api.StatisticsQuery("atlas:world_operation_duration_ms", 1,
                    Optional.empty(), Optional.empty(), Optional.empty(), java.util.Map.of("backend", "EU"));
            var durationNa = new nl.pixelretreat.logbook.api.StatisticsQuery("atlas:world_operation_duration_ms", 1,
                    Optional.empty(), Optional.empty(), Optional.empty(), java.util.Map.of("backend", "NA"));
            var durationNetwork = new nl.pixelretreat.logbook.api.StatisticsQuery("atlas:world_operation_duration_ms", 1,
                    Optional.empty(), Optional.empty(), Optional.empty(), java.util.Map.of());
            var durationValues = reads.readDistributions(java.util.Set.of(durationEu, durationNa, durationNetwork));
            assertEquals(expectedEuSum, durationValues.get(durationEu).totals().orElseThrow().sum());
            assertEquals(expectedEuCount, durationValues.get(durationEu).totals().orElseThrow().sampleCount());
            assertEquals(expectedNaSum, durationValues.get(durationNa).totals().orElseThrow().sum());
            assertEquals(expectedNaCount, durationValues.get(durationNa).totals().orElseThrow().sampleCount());
            assertEquals(expectedNetworkSum, durationValues.get(durationNetwork).totals().orElseThrow().sum());
            assertEquals(expectedEuCount.add(expectedNaCount), durationValues.get(durationNetwork).totals().orElseThrow().sampleCount());
            var unknownDuration = reads.readDistributions(java.util.Set.of(absent)).get(absent);
            assertEquals(nl.pixelretreat.logbook.api.StatisticValue.Status.UNKNOWN, unknownDuration.status());
            assertTrue(unknownDuration.totals().isEmpty(), "an absent definition must not become a zero duration summary");            int archiveCount = count(pool, "SELECT COUNT(*) FROM logbook_events WHERE owner='atlas'");
            collector.drain("atlas", 100);
            assertEquals(archiveCount, count(pool, "SELECT COUNT(*) FROM logbook_events WHERE owner='atlas'"), "collector replay cannot duplicate original facts");
            var replayed = reads.readDistributions(java.util.Set.of(durationNetwork)).get(durationNetwork).totals().orElseThrow();
            assertEquals(expectedNetworkSum, replayed.sum(), "replay cannot change the original duration sum");
            assertEquals(expectedEuCount.add(expectedNaCount), replayed.sampleCount(), "replay cannot change the original duration denominator");
        }
    }

    @Test void supersededOlderRouteKeepsItsUnknownRootGapAndTerminalHolds() throws Exception {
        String url = fixtureJdbcUrl;
        assumeTrue(url != null && !url.isBlank(), "Use an isolated ATLAS_TEST_JDBC_URL");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(0);

        try (HikariDataSource pool = new HikariDataSource(config);
             AtlasWorkers workers = new AtlasWorkers(2, 32)) {
            AtlasRepository eu = new AtlasRepository(pool, "EU");
            eu.initialize();
            AtlasStatistics euStats = statistics(pool, "EU", ProducerLimits.APPROVED_START, workers);
            eu.statistics(euStats);
            AtlasRepository na = new AtlasRepository(pool, "NA");
            AtlasStatistics naStats = statistics(pool, "NA", ProducerLimits.APPROVED_START, workers);
            na.statistics(naStats);

            UUID player = UUID.randomUUID();
            TravelTicket older = new TravelTicket(UUID.randomUUID(), player, "old_gate", "atlas:old", Optional.empty());
            eu.issueTicket(older, "NA", Instant.now().plusSeconds(60), "minecraft:overworld");
            eu.unresolvedPortalTrip(older.id(), "proxy_send_unconfirmed");
            Thread.sleep(12);
            TravelTicket latest = new TravelTicket(UUID.randomUUID(), player, "new_gate", "atlas:new", Optional.empty());
            eu.issueTicket(latest, "NA", Instant.now().plusSeconds(60), "minecraft:overworld");

            assertEquals(latest, na.takeTicket(player).orElseThrow());
            assertTrue(na.takeTicket(player).isEmpty());
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_travel_ticket WHERE id=? AND consumed_at IS NULL AND expires_at <= CURRENT_TIMESTAMP(3)",
                    older.id().toString()));
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_portal_trips WHERE operation_id=? AND terminal_at IS NULL AND terminal_outcome IS NULL",
                    older.id().toString()), "supersession must not terminalize the old original root");

            assertTrue(outboxContains(pool, event("atlas:portal_routes", older.id(), "accepted", player)));
            UUID oldGap = event("atlas:collection_gaps", older.id(), "unresolved-gap", player);
            assertTrue(outboxContains(pool, oldGap), "the original UNKNOWN gap remains durable");
            assertTrue(facts(pool).stream().anyMatch(fact -> oldGap.equals(fact.eventId())
                    && "proxy_send_unconfirmed".equals(fact.dimensions().get("cause"))));
            UUID terminal = event("atlas:portal_routes", older.id(), "terminal", player);
            UUID duration = event("atlas:portal_duration_ms", older.id(), "duration", player);
            assertFalse(outboxContains(pool, terminal), "supersession cannot claim a terminal result");
            assertFalse(outboxContains(pool, duration), "supersession cannot fabricate route duration");
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM logbook_admission WHERE owner='atlas' AND event_id=? AND state='HELD'",
                    terminal.toString()), "the original terminal hold stays reserved");
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM logbook_admission WHERE owner='atlas' AND event_id=? AND state='HELD'",
                    duration.toString()), "the original duration hold stays reserved");
        }
    }

    @Test void ambiguousProxySendPreservesTheOriginalTicketForArrivalWithoutIssuingARetry() throws Exception {
        String url = fixtureJdbcUrl;
        assumeTrue(url != null && !url.isBlank(), "Use an isolated ATLAS_TEST_JDBC_URL");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(6);
        config.setMinimumIdle(0);

        try (HikariDataSource pool = new HikariDataSource(config);
             AtlasWorkers workers = new AtlasWorkers(2, 32)) {
            AtlasRepository eu = new AtlasRepository(pool, "EU");
            eu.initialize();
            AtlasStatistics euStats = statistics(pool, "EU", ProducerLimits.APPROVED_START, workers);
            eu.statistics(euStats);
            AtlasRepository na = new AtlasRepository(pool, "NA");
            AtlasStatistics naStats = statistics(pool, "NA", ProducerLimits.APPROVED_START, workers);
            na.statistics(naStats);

            UUID player = UUID.randomUUID();
            TravelTicket original = new TravelTicket(UUID.randomUUID(), player, "gate", "atlas:destination", Optional.empty());
            eu.issueTicket(original, "NA", Instant.now().plusSeconds(60), "minecraft:overworld");
            assertTrue(eu.withdrawTicket(original.id(), false), "the ambiguous route remains pending");

            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_travel_ticket WHERE id=? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP(3)",
                    original.id().toString()), "an uncertain send cannot discard the only original routing intent");
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_portal_trips WHERE operation_id=? AND terminal_at IS NULL",
                    original.id().toString()), "the UNKNOWN route root remains open");
            UUID gap = event("atlas:collection_gaps", original.id(), "unresolved-gap", player);
            assertTrue(outboxContains(pool, gap));
            assertTrue(facts(pool).stream().anyMatch(fact -> gap.equals(fact.eventId())
                    && "proxy_send_unconfirmed".equals(fact.dimensions().get("cause"))));
            assertFalse(outboxContains(pool, event("atlas:portal_routes", original.id(), "terminal", player)));
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM logbook_admission WHERE owner='atlas' AND event_id=? AND state='HELD'",
                    event("atlas:portal_routes", original.id(), "terminal", player).toString()));
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM logbook_admission WHERE owner='atlas' AND event_id=? AND state='HELD'",
                    event("atlas:portal_duration_ms", original.id(), "duration", player).toString()));

            assertEquals(original, na.takeTicket(player).orElseThrow(), "arrival consumes the same original UUID, not a retry");
            na.finishPortalTrip(original.id(), "arrived");
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_portal_trips WHERE operation_id=? AND terminal_outcome='arrived'",
                    original.id().toString()));
            assertTrue(outboxContains(pool, event("atlas:portal_routes", original.id(), "terminal", player)));
            assertTrue(outboxContains(pool, gap), "the historical uncertainty fact remains visible after later resolution");

            UUID rejectedPlayer = UUID.randomUUID();
            TravelTicket rejected = new TravelTicket(UUID.randomUUID(), rejectedPlayer, "rejected_gate",
                    "atlas:destination", Optional.empty());
            eu.issueTicket(rejected, "NA", Instant.now().plusSeconds(60), "minecraft:overworld");
            assertTrue(eu.withdrawTicket(rejected.id(), true), "a proven pre-send rejection removes the ticket");
            assertEquals(0, count(pool, "SELECT COUNT(*) FROM atlas_travel_ticket WHERE id=?", rejected.id().toString()));
            assertEquals(1, count(pool, "SELECT COUNT(*) FROM atlas_logbook_portal_trips WHERE operation_id=? AND terminal_outcome='not_performed'",
                    rejected.id().toString()));
            assertFalse(outboxContains(pool, event("atlas:collection_gaps", rejected.id(), "unresolved-gap", rejectedPlayer)));
        }
    }

    private static AtlasStatistics statistics(HikariDataSource pool, String backend, ProducerLimits limits,
                                              AtlasWorkers workers) throws SQLException {
        var statistics = new AtlasStatistics(pool, limits, backend, 64, () -> { });
        statistics.attachWorker(workers::submit);
        statistics.initialize();
        statistics.beginCollection();
        return statistics;
    }

    private static AtlasStatistics statistics(HikariDataSource pool, String backend, ProducerLimits limits,
                                              AtlasWorkers workers, Clock clock) throws SQLException {
        var statistics = new AtlasStatistics(pool, limits, backend, 64, () -> { }, clock);
        statistics.attachWorker(workers::submit);
        statistics.initialize();
        statistics.beginCollection();
        return statistics;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;
        MutableClock(Instant current) { this.current = current; }
        void set(Instant instant) { current = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    private static UUID event(String metric, UUID operation, String stage, UUID player) {
        return FactIdentity.of(metric, operation, "atlas/" + metric + "/" + stage, player);
    }

    private static int count(HikariDataSource pool, String sql, Object... parameters) throws SQLException {
        try (Connection connection = pool.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) query.setObject(index + 1, parameters[index]);
            try (ResultSet row = query.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private static String string(HikariDataSource pool, String sql, Object... parameters) throws SQLException {
        try (Connection connection = pool.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) query.setObject(index + 1, parameters[index]);
            try (ResultSet row = query.executeQuery()) { if (!row.next()) throw new SQLException("Expected row"); return row.getString(1); }
        }
    }

    private static boolean outboxContains(HikariDataSource pool, UUID event) throws SQLException {
        return count(pool, "SELECT COUNT(*) FROM logbook_outbox WHERE owner='atlas' AND event_id=?", event.toString()) == 1;
    }

    private static List<Fact> facts(HikariDataSource pool) throws SQLException {
        List<Fact> facts = new ArrayList<>();
        try (Connection connection = pool.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT body FROM logbook_outbox WHERE owner='atlas'")) {
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) facts.add(CanonicalCodec.decode(rows.getBytes(1), 32768));
            }
        }
        return facts;
    }

    private static boolean awaitFacts(HikariDataSource pool, Predicate<List<Fact>> predicate, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            if (predicate.test(facts(pool))) return true;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return predicate.test(facts(pool));
    }

    private static boolean awaitQuery(HikariDataSource pool, String sql, Object parameter,
                                      java.util.function.IntPredicate predicate, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            if (predicate.test(count(pool, sql, parameter))) return true;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return predicate.test(count(pool, sql, parameter));
    }
}
