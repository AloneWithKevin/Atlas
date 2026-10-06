package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import nl.pixelretreat.atlas.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real SQL state transitions with process-interruption boundaries and actual directory moves. */
final class WorldCopyRecoveryMariaDbTest {
    @TempDir Path directory;
    static final class Crash extends Error { }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of(false, WorldCopyRecovery.Boundary.PREPARED),
                Arguments.of(false, WorldCopyRecovery.Boundary.NEW_MOVED),
                Arguments.of(false, WorldCopyRecovery.Boundary.PUBLISHED),
                Arguments.of(true, WorldCopyRecovery.Boundary.PREPARED),
                Arguments.of(true, WorldCopyRecovery.Boundary.OLD_MOVED),
                Arguments.of(true, WorldCopyRecovery.Boundary.NEW_MOVED),
                Arguments.of(true, WorldCopyRecovery.Boundary.PUBLISHED));
    }

    @ParameterizedTest @MethodSource("boundaries")
    void resumesOnlySealedTreeAndNeverReplaysAppliedWorld(boolean reset, WorldCopyRecovery.Boundary boundary) throws Exception {
        try (Fixture f = new Fixture(reset)) {
            f.pack.synchronize(f.repository.loadWorlds().values());
            assertFalse(f.pack.declaredWorlds().contains("atlas:target"));
            f.crash(boundary);
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
            Files.writeString(f.source.resolve("region/chunk"), "later source");
            new WorldCopyRecovery(f.repository, f.folders).run(f.operation);
            assertEquals("prepared", Files.readString(f.target.resolve("region/chunk")));
            assertFalse(Files.exists(f.target.resolve("uid.dat")));
            assertTrue(Files.isDirectory(f.target.resolve("empty")));
            assertTrue(f.repository.pending().isEmpty());
            assertEquals(WorldCopyState.Phase.APPLIED, f.repository.copyState(f.operation.id()).orElseThrow().phase());
            assertTrue(f.repository.loadWorlds().get("atlas:target").enabled());
            if (reset) assertEquals("original", Files.readString(f.folders.backup(f.operation).resolve("region/chunk")));
            Files.writeString(f.target.resolve("region/chunk"), "game edit");
            new WorldCopyRecovery(f.repository, f.folders).run(f.operation);
            assertEquals("game edit", Files.readString(f.target.resolve("region/chunk")));
            f.pack.synchronize(f.repository.loadWorlds().values());
            assertTrue(f.pack.declaredWorlds().contains("atlas:target"));
            assertTrue(new StartupOperations(f.repository, f.folders, f.pack.declaredWorlds()).run().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"changed", "missing", "extra"})
    void damagedPreparedEvidenceKeepsOldWorldAndRequiresDecision(String damage) throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.crash(WorldCopyRecovery.Boundary.PREPARED);
            Path staging = f.folders.staging(f.operation);
            switch (damage) {
                case "changed" -> Files.writeString(staging.resolve("region/chunk"), "changed");
                case "missing" -> Files.delete(staging.resolve("region/chunk"));
                case "extra" -> Files.writeString(staging.resolve("extra"), "extra");
            }
            var outcomes = new StartupOperations(f.repository, f.folders).run();
            assertFalse(outcomes.getFirst().success());
            assertEquals("UNCERTAIN", outcomes.getFirst().detail());
            assertEquals("original", Files.readString(f.target.resolve("region/chunk")));
            assertTrue(Files.exists(staging));
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
            assertThrows(java.sql.SQLException.class, () -> f.repository.setEnabled("atlas:target", true));
            assertTrue(new StartupOperations(f.repository, f.folders).run().isEmpty());
        }
    }

    @Test void unsealedPreparationIsNotRecopiedAndCannotBeCancelledAfterClaim() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.repository.advanceCopy(f.operation.id(), WorldCopyState.Phase.QUEUED, WorldCopyState.Phase.COPYING);
            Path staging = f.folders.staging(f.operation);
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("partial"), "preserve");
            assertThrows(java.sql.SQLException.class, () -> f.repository.cancel(f.operation.id()));
            assertFalse(new StartupOperations(f.repository, f.folders).run().getFirst().success());
            assertEquals("preserve", Files.readString(staging.resolve("partial")));
            assertEquals("original", Files.readString(f.target.resolve("region/chunk")));
            assertEquals(WorldCopyState.Phase.UNCERTAIN, f.repository.copyState(f.operation.id()).orElseThrow().phase());
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
        }
    }

    @Test void contradictoryPublicationPathsArePreservedInsteadOfOverwritten() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.crash(WorldCopyRecovery.Boundary.NEW_MOVED);
            Files.createDirectories(f.folders.staging(f.operation));
            Files.writeString(f.folders.staging(f.operation).resolve("unexpected"), "preserve");
            assertFalse(new StartupOperations(f.repository, f.folders).run().getFirst().success());
            assertEquals("prepared", Files.readString(f.target.resolve("region/chunk")));
            assertEquals("original", Files.readString(f.folders.backup(f.operation).resolve("region/chunk")));
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
        }
    }

    @Test void staleStartupDeclarationDefersAllFolderWorkUntilUndeclaredStart() throws Exception {
        try (Fixture f = new Fixture(true)) {
            var result = new StartupOperations(f.repository, f.folders, java.util.Set.of("atlas:target")).run();
            assertEquals("PENDING", result.getFirst().detail());
            assertEquals(WorldCopyState.Phase.QUEUED, f.repository.copyState(f.operation.id()).orElseThrow().phase());
            assertEquals("original", Files.readString(f.target.resolve("region/chunk")));
            assertFalse(Files.exists(f.folders.staging(f.operation)));
            f.pack.synchronize(f.repository.loadWorlds().values());
            assertFalse(f.pack.declaredWorlds().contains("atlas:target"));
            assertTrue(new StartupOperations(f.repository, f.folders, f.pack.declaredWorlds()).run().getFirst().success());
            assertFalse(f.pack.declaredWorlds().contains("atlas:target"), "already read pack remains undeclared");
            f.pack.synchronize(f.repository.loadWorlds().values());
            assertTrue(f.pack.declaredWorlds().contains("atlas:target"), "following start can activate");
        }
    }

    @Test void legacyOperationWithoutProofNeverAdoptsOrDeletesExistingFiles() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.repository.cancel(f.operation.id());
            f.repository.insertWorld("atlas:target", WorldGenerator.VOID, Optional.empty());
            Files.createDirectories(f.target);
            Files.writeString(f.target.resolve("unknown"), "preserve");
            long legacy = f.repository.queue(PendingOperation.Kind.CLONE, "atlas:target", Optional.of("atlas:source"), UUID.randomUUID());
            assertFalse(new StartupOperations(f.repository, f.folders).run().getFirst().success());
            assertEquals("preserve", Files.readString(f.target.resolve("unknown")));
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
            assertEquals(WorldCopyState.Phase.UNCERTAIN, f.repository.copyState(legacy).orElseThrow().phase());
        }
    }

    @Test void completionWriteFailureRollsBackActivationAndCanResumePublishedTree() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.crash(WorldCopyRecovery.Boundary.PUBLISHED);
            javax.sql.DataSource injected = org.mockito.Mockito.mock(javax.sql.DataSource.class);
            org.mockito.Mockito.when(injected.getConnection()).thenAnswer(ignored -> {
                java.sql.Connection actual = f.pool.getConnection();
                java.sql.Connection proxy = org.mockito.Mockito.mock(java.sql.Connection.class,
                        org.mockito.AdditionalAnswers.delegatesTo(actual));
                org.mockito.Mockito.doThrow(new java.sql.SQLException("injected receipt failure"))
                        .when(proxy).prepareStatement(org.mockito.ArgumentMatchers.startsWith(
                                "UPDATE atlas_pending_operation SET state = ?"));
                return proxy;
            });
            var failing = new AtlasRepository(injected, f.server);
            assertThrows(java.sql.SQLException.class, () -> failing.completeCopy(f.operation));
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
            assertEquals(WorldCopyState.Phase.PUBLISHED, f.repository.copyState(f.operation.id()).orElseThrow().phase());
            assertEquals(f.operation.id(), f.repository.pending().getFirst().id());
            new WorldCopyRecovery(f.repository, f.folders).run(f.operation);
            assertTrue(f.repository.pending().isEmpty());
            assertTrue(f.repository.loadWorlds().get("atlas:target").enabled());
        }
    }

    @Test void cancellingDisabledResetRestoresDisabledStateAndAllowsLaterActivation() throws Exception {
        try (Fixture f = new Fixture(true)) {
            assertTrue(f.repository.cancel(f.operation.id()).isPresent());
            assertTrue(f.repository.loadWorlds().get("atlas:target").enabled());
            f.repository.setEnabled("atlas:target", false);
            long second = f.repository.queueReset("atlas:target", "atlas:source", UUID.randomUUID());
            assertTrue(f.repository.cancel(second).isPresent());
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
            assertTrue(f.repository.setEnabled("atlas:target", true));
            assertEquals("original", Files.readString(f.target.resolve("region/chunk")));
        }
    }

    @Test void pendingDeletionCannotBeFollowedByDestructiveResetOrDuplicateDelete() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.repository.cancel(f.operation.id());
            assertTrue(f.repository.disableAndQueueDelete("atlas:target", UUID.randomUUID()));
            assertThrows(java.sql.SQLException.class, () -> f.repository.queueReset("atlas:target", "atlas:source", UUID.randomUUID()));
            assertThrows(java.sql.SQLException.class, () -> f.repository.disableAndQueueDelete("atlas:target", UUID.randomUUID()));
            assertEquals(1, f.repository.pending().size());
            assertFalse(f.repository.loadWorlds().get("atlas:target").enabled());
            assertEquals("original", Files.readString(f.target.resolve("region/chunk")));
        }
    }

    final class Fixture implements AutoCloseable {
        final HikariDataSource pool;
        final String server = "R" + UUID.randomUUID().toString().substring(0, 6);
        final AtlasRepository repository;
        final WorldFolders folders = new WorldFolders(directory.resolve("dimensions"), 2);
        final DatapackWriter pack = new DatapackWriter(directory.resolve("pack"), 71);
        final Path source;
        final Path target;
        final PendingOperation operation;

        Fixture(boolean reset) throws Exception {
            String url = System.getenv("ATLAS_TEST_JDBC_URL");
            assumeTrue(url != null && !url.isBlank(), "Use isolated ATLAS_TEST_JDBC_URL");
            var config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
            config.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
            config.setMaximumPoolSize(2);
            pool = new HikariDataSource(config);
            repository = new AtlasRepository(pool, server);
            repository.initialize();
            repository.insertWorld("atlas:source", WorldGenerator.VOID, Optional.empty());
            source = folders.folder("atlas:source");
            target = folders.folder("atlas:target");
            Files.createDirectories(source.resolve("region"));
            Files.createDirectories(source.resolve("empty"));
            Files.writeString(source.resolve("region/chunk"), "prepared");
            Files.writeString(source.resolve("uid.dat"), "identity");
            if (reset) {
                repository.insertWorld("atlas:target", WorldGenerator.VOID, Optional.empty());
                Files.createDirectories(target.resolve("region"));
                Files.writeString(target.resolve("region/chunk"), "original");
                Files.writeString(target.resolve("uid.dat"), "old identity");
                repository.queueReset("atlas:target", "atlas:source", UUID.randomUUID());
            } else repository.insertAndQueueClone("atlas:target", WorldGenerator.VOID, "atlas:source", UUID.randomUUID());
            operation = repository.pending().getFirst();
        }

        void crash(WorldCopyRecovery.Boundary boundary) {
            assertThrows(Crash.class, () -> new WorldCopyRecovery(repository, folders, reached -> {
                if (reached == boundary) throw new Crash();
            }).run(operation));
        }
        public void close() { pool.close(); }
    }
}
