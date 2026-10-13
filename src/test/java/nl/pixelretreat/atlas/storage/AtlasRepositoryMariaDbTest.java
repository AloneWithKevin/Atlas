package nl.pixelretreat.atlas.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import nl.pixelretreat.atlas.api.AtlasFlag;
import nl.pixelretreat.atlas.keeploaded.KeepLoadedRegion;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.portal.TravelTicket;
import nl.pixelretreat.atlas.service.StartupOperations;
import nl.pixelretreat.atlas.world.PendingOperation;
import nl.pixelretreat.atlas.world.SpawnPoint;
import nl.pixelretreat.atlas.world.WeatherMode;
import nl.pixelretreat.atlas.world.WorldFolders;
import nl.pixelretreat.atlas.world.WorldGenerator;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs against a real, isolated MariaDB only when ATLAS_TEST_JDBC_URL is set. */
final class AtlasRepositoryMariaDbTest {
    @TempDir Path dimensions;

    @Test void worldsPortalsRegionsTicketsAndStartupOperations() throws Exception {
        String url = System.getenv("ATLAS_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "Set an isolated ATLAS_TEST_JDBC_URL to run MariaDB tests");
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(url);
        poolConfig.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
        poolConfig.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
        poolConfig.setMaximumPoolSize(2);
        try (HikariDataSource pool = new HikariDataSource(poolConfig)) {
            // A random region code keeps runs apart; production only uses EU and NA.
            String server = "T" + UUID.randomUUID().toString().substring(0, 6);
            String other = "U" + UUID.randomUUID().toString().substring(0, 6);
            AtlasRepository repository = new AtlasRepository(pool, server);
            repository.initialize();

            assertTrue(repository.insertWorld("atlas:arena", WorldGenerator.VOID, Optional.empty()));
            assertFalse(repository.insertWorld("atlas:arena", WorldGenerator.FLAT, Optional.empty()));
            repository.setFlag("minecraft:overworld", AtlasFlag.PVP, false);
            repository.setFlag("atlas:arena", AtlasFlag.BLOCK_BREAK, true);
            repository.setFlag("atlas:arena", AtlasFlag.BLOCK_BREAK, null);
            repository.setFlag("atlas:arena", AtlasFlag.TRADING, false);
            assertFalse(repository.loadWorlds().get("atlas:arena").flags().get(AtlasFlag.TRADING),
                    "a trading override persists");
            repository.setFlag("atlas:arena", AtlasFlag.TRADING, null);
            assertFalse(repository.loadWorlds().get("atlas:arena").flags().containsKey(AtlasFlag.TRADING),
                    "default removes the trading override instead of storing the default");
            repository.setSetting("atlas:arena", AtlasRepository.Setting.FIXED_TIME, 6000L);
            repository.setSetting("atlas:arena", AtlasRepository.Setting.GAME_MODE, GameMode.ADVENTURE);
            repository.setSetting("atlas:arena", AtlasRepository.Setting.WEATHER, WeatherMode.CLEAR);
            repository.setSpawn("atlas:arena", new SpawnPoint(0.5, 80, 0.5, 90, 0));
            var worlds = repository.loadWorlds();
            assertFalse(worlds.get("minecraft:overworld").flags().get(AtlasFlag.PVP));
            assertTrue(worlds.get("minecraft:overworld").generator().isEmpty());
            var arena = worlds.get("atlas:arena");
            assertEquals(Optional.of(6000L), arena.fixedTime());
            assertEquals(Optional.of(GameMode.ADVENTURE), arena.gameMode());
            assertTrue(arena.flags().isEmpty());
            assertEquals(90f, arena.spawn().orElseThrow().yaw());
            assertFalse(repository.setEnabled("minecraft:overworld", false));

            Portal portal = new Portal("gate", "minecraft:overworld", 0, 60, 0, 2, 63, 0,
                    new PortalTarget(PortalTarget.Kind.SERVER, Optional.of(other), "atlas:hub", Optional.empty()),
                    2500, Optional.of("minecraft:block.portal.travel"), Optional.of("PORTAL"), true, Optional.of("NETHER_PORTAL"));
            repository.savePortal(portal);
            assertFalse(repository.insertPortal(portal.withCooldown(123)), "same-name creation must not replace a portal");
            assertEquals(portal, repository.loadPortals().getFirst());
            assertTrue(repository.deletePortal("gate"));
            assertFalse(repository.deletePortal("gate"));

            repository.saveRegion(KeepLoadedRegion.fromBlocks("hub", "minecraft:overworld", 0, 0, 31, 31));
            assertEquals(4, repository.loadRegions().getFirst().chunkCount());
            assertTrue(repository.deleteRegion("hub"));

            // A reference created after queuing deletion must be rechecked before folder removal.
            assertTrue(repository.insertWorld("atlas:guarded", WorldGenerator.VOID, Optional.empty()));
            Path guarded = dimensions.resolve("atlas/guarded/region");
            Files.createDirectories(guarded);
            Files.writeString(guarded.resolve("chunk"), "retained");
            assertTrue(repository.disableAndQueueDelete("atlas:guarded", UUID.randomUUID()));
            repository.savePortal(new Portal("late", "atlas:guarded", 0, 60, 0, 1, 63, 0,
                    PortalTarget.spawn("minecraft:overworld"), 2500, Optional.empty(), Optional.empty(), false, Optional.empty()));
            var guardedOutcomes = new StartupOperations(repository, new WorldFolders(dimensions, 1)).run();
            assertEquals("world.error.has-portals", guardedOutcomes.getFirst().detail());
            assertEquals("retained", Files.readString(guarded.resolve("chunk")));
            repository.deletePortal("late");
            assertTrue(repository.disableAndQueueDelete("atlas:guarded", UUID.randomUUID()));
            repository.saveRegion(KeepLoadedRegion.fromBlocks("late", "atlas:guarded", 0, 0, 15, 15));
            guardedOutcomes = new StartupOperations(repository, new WorldFolders(dimensions, 1)).run();
            assertEquals("world.error.has-regions", guardedOutcomes.getFirst().detail());
            assertEquals("retained", Files.readString(guarded.resolve("chunk")));
            repository.deleteRegion("late");
            assertTrue(repository.disableAndQueueDelete("atlas:guarded", UUID.randomUUID()));
            assertTrue(new StartupOperations(repository, new WorldFolders(dimensions, 1)).run().getFirst().success());
            assertFalse(Files.exists(guarded));

            UUID player = UUID.randomUUID();
            TravelTicket ticket = new TravelTicket(UUID.randomUUID(), player, "gate", "atlas:hub", Optional.empty());
            AtlasRepository destination = new AtlasRepository(pool, other);
            repository.issueTicket(ticket, other, Instant.now().plusSeconds(60));
            assertTrue(repository.takeTicket(player).isEmpty(), "a ticket is only valid on its target server");
            assertEquals(ticket.id(), destination.takeTicket(player).orElseThrow().id());
            assertTrue(destination.takeTicket(player).isEmpty(), "a ticket is consumed once");

            Path template = dimensions.resolve("atlas/arena/region");
            Files.createDirectories(template);
            Files.writeString(template.resolve("r.0.0.mca"), "template");
            assertTrue(repository.insertAndQueueClone("atlas:copy", WorldGenerator.VOID, "atlas:arena", player));
            long cancelled = repository.queueReset("atlas:arena", "atlas:template", player);
            assertTrue(repository.cancel(cancelled).isPresent());
            repository.setEnabled("atlas:arena", false);
            repository.queue(PendingOperation.Kind.DELETE, "atlas:arena", Optional.empty(), player);
            var outcomes = new StartupOperations(repository, new WorldFolders(dimensions, 2)).run();
            assertEquals(2, outcomes.size());
            assertTrue(outcomes.stream().allMatch(StartupOperations.Outcome::success), outcomes.toString());
            assertEquals("template", Files.readString(dimensions.resolve("atlas/copy/region/r.0.0.mca")));
            assertFalse(Files.exists(dimensions.resolve("atlas/arena")));
            assertFalse(repository.loadWorlds().containsKey("atlas:arena"));
            assertTrue(repository.pending().isEmpty());
        }
    }

    @Test void consumingNewestTicketExpiresOlderPendingTicketsForTheSamePlayerAndTarget() throws Exception {
        String url = System.getenv("ATLAS_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "Set an isolated ATLAS_TEST_JDBC_URL to run MariaDB tests");
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(url);
        poolConfig.setUsername(System.getenv().getOrDefault("ATLAS_TEST_DB_USER", "root"));
        poolConfig.setPassword(System.getenv().getOrDefault("ATLAS_TEST_DB_PASSWORD", ""));
        poolConfig.setMaximumPoolSize(3);
        try (HikariDataSource pool = new HikariDataSource(poolConfig)) {
            AtlasRepository origin = new AtlasRepository(pool, "EU");
            AtlasRepository destination = new AtlasRepository(pool, "NA");
            origin.initialize();

            UUID player = UUID.randomUUID();
            TravelTicket older = new TravelTicket(UUID.randomUUID(), player, "old_gate", "atlas:old", Optional.empty());
            origin.issueTicket(older, "NA", Instant.now().plusSeconds(60));
            Thread.sleep(12);
            TravelTicket latest = new TravelTicket(UUID.randomUUID(), player, "new_gate", "atlas:new", Optional.empty());
            origin.issueTicket(latest, "NA", Instant.now().plusSeconds(60));
            UUID unrelatedPlayer = UUID.randomUUID();
            TravelTicket unrelated = new TravelTicket(UUID.randomUUID(), unrelatedPlayer, "other_gate",
                    "atlas:unrelated", Optional.empty());
            origin.issueTicket(unrelated, "NA", Instant.now().plusSeconds(60));

            assertEquals(latest, destination.takeTicket(player).orElseThrow(),
                    "the existing newest-on-arrival selection still chooses the latest ticket");
            assertTrue(destination.takeTicket(player).isEmpty(),
                    "an older route cannot be resurrected by a later unrelated target join");
            assertEquals(unrelated, destination.takeTicket(unrelatedPlayer).orElseThrow(),
                    "supersession is isolated to the same player and target region");

            try (java.sql.Connection connection = pool.getConnection();
                 java.sql.PreparedStatement query = connection.prepareStatement(
                         "SELECT expires_at <= CURRENT_TIMESTAMP(3), consumed_at IS NULL FROM atlas_travel_ticket WHERE id=?")) {
                query.setString(1, older.id().toString());
                try (java.sql.ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertTrue(row.getBoolean(1), "the older ticket expires in the consume transaction");
                    assertTrue(row.getBoolean(2), "the old ticket remains unconsumed, not falsely terminal");
                }
            }

            UUID tiedPlayer = UUID.randomUUID();
            TravelTicket tiedOne = new TravelTicket(UUID.randomUUID(), tiedPlayer, "tie_one", "atlas:one", Optional.empty());
            TravelTicket tiedTwo = new TravelTicket(UUID.randomUUID(), tiedPlayer, "tie_two", "atlas:two", Optional.empty());
            origin.issueTicket(tiedOne, "NA", Instant.now().plusSeconds(60));
            origin.issueTicket(tiedTwo, "NA", Instant.now().plusSeconds(60));
            try (java.sql.Connection connection = pool.getConnection();
                 java.sql.PreparedStatement update = connection.prepareStatement(
                         "UPDATE atlas_travel_ticket SET created_at=CURRENT_TIMESTAMP(3) WHERE id IN (?,?)")) {
                update.setString(1, tiedOne.id().toString());
                update.setString(2, tiedTwo.id().toString());
                assertEquals(2, update.executeUpdate());
            }
            TravelTicket tiedWinner = destination.takeTicket(tiedPlayer).orElseThrow();
            assertTrue(tiedWinner.id().equals(tiedOne.id()) || tiedWinner.id().equals(tiedTwo.id()),
                    "equal created_at values keep the existing unspecified winner");
            assertTrue(destination.takeTicket(tiedPlayer).isEmpty(), "the unselected timestamp tie cannot be resurrected");
            UUID tiedLoser = tiedWinner.id().equals(tiedOne.id()) ? tiedTwo.id() : tiedOne.id();
            try (java.sql.Connection connection = pool.getConnection();
                 java.sql.PreparedStatement query = connection.prepareStatement(
                         "SELECT expires_at <= CURRENT_TIMESTAMP(3), consumed_at IS NULL FROM atlas_travel_ticket WHERE id=?")) {
                query.setString(1, tiedLoser.toString());
                try (java.sql.ResultSet row = query.executeQuery()) {
                    assertTrue(row.next());
                    assertTrue(row.getBoolean(1));
                    assertTrue(row.getBoolean(2));
                }
            }
        }
    }
}
