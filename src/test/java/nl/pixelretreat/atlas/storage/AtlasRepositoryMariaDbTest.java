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
            assertEquals(portal, repository.loadPortals().getFirst());
            assertTrue(repository.deletePortal("gate"));
            assertFalse(repository.deletePortal("gate"));

            repository.saveRegion(KeepLoadedRegion.fromBlocks("hub", "minecraft:overworld", 0, 0, 31, 31));
            assertEquals(4, repository.loadRegions().getFirst().chunkCount());
            assertTrue(repository.deleteRegion("hub"));

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
}
