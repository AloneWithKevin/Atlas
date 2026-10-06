package nl.pixelretreat.atlas.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.WorldGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

/** Actual MariaDB rollback and cancellation under injected command-write failures. */
final class WorldQueueAtomicityMariaDbTest {
    @Test void queueFailuresRollBackDeclarationsAndCancellationRollsBackItsWorldChange() throws Exception {
        String url = System.getenv("ATLAS_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "Use an isolated ATLAS_TEST_JDBC_URL");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
        config.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
        config.setMaximumPoolSize(2);
        try (var pool = new HikariDataSource(config)) {
            String server = "A" + UUID.randomUUID().toString().substring(0, 6);
            var repository = new AtlasRepository(pool, server);
            repository.initialize();
            var failQueue = new AtlasRepository(failing(pool, true), server);
            UUID actor = UUID.randomUUID();
            assertThrows(SQLException.class, () -> failQueue.insertAndQueueClone(
                    "atlas:copy", WorldGenerator.VOID, "atlas:template", actor));
            assertFalse(repository.loadWorlds().containsKey("atlas:copy"));
            assertTrue(repository.pending().isEmpty());

            assertTrue(repository.insertWorld("atlas:arena", WorldGenerator.VOID, Optional.empty()));
            assertThrows(SQLException.class, () -> failQueue.disableAndQueueDelete("atlas:arena", actor));
            assertTrue(repository.loadWorlds().get("atlas:arena").enabled());
            assertTrue(repository.pending().isEmpty());

            assertTrue(repository.insertAndQueueClone("atlas:copy", WorldGenerator.VOID, "atlas:arena", actor));
            repository.setFlag("atlas:copy", AtlasFlag.PVP, false);
            long clone = repository.pending().getFirst().id();
            var failCancel = new AtlasRepository(failing(pool, false), server);
            assertThrows(SQLException.class, () -> failCancel.cancel(clone));
            assertTrue(repository.loadWorlds().containsKey("atlas:copy"));
            assertFalse(repository.loadWorlds().get("atlas:copy").flags().get(AtlasFlag.PVP));
            assertEquals(clone, repository.pending().getFirst().id());
            assertTrue(repository.cancel(clone).isPresent());
            assertFalse(repository.loadWorlds().containsKey("atlas:copy"));
            assertFalse(repository.cancel(clone).isPresent());

            assertTrue(repository.disableAndQueueDelete("atlas:arena", actor));
            long deletion = repository.pending().getFirst().id();
            assertFalse(repository.loadWorlds().get("atlas:arena").enabled());
            assertThrows(SQLException.class, () -> failCancel.cancel(deletion));
            assertFalse(repository.loadWorlds().get("atlas:arena").enabled());
            assertTrue(repository.cancel(deletion).isPresent());
            assertTrue(repository.loadWorlds().get("atlas:arena").enabled());
            assertTrue(repository.pending().isEmpty());
            assertFalse(repository.disableAndQueueDelete("atlas:missing", actor));
        }
    }

    private static DataSource failing(DataSource actual, boolean queue) throws SQLException {
        DataSource injected = mock(DataSource.class);
        when(injected.getConnection()).thenAnswer(invocation -> {
            Connection connection = actual.getConnection();
            Connection proxy = mock(Connection.class, AdditionalAnswers.delegatesTo(connection));
            if (queue) {
                doThrow(new SQLException("injected queue failure")).when(proxy).prepareStatement(
                        startsWith("INSERT INTO atlas_pending_operation"), eq(Statement.RETURN_GENERATED_KEYS));
            } else {
                doThrow(new SQLException("injected cancellation failure")).when(proxy).prepareStatement(
                        startsWith("UPDATE atlas_pending_operation SET state = 'CANCELLED'"));
            }
            return proxy;
        });
        return injected;
    }
}
