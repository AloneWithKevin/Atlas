package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import nl.pixelretreat.atlas.config.AtlasConfig;
import nl.pixelretreat.atlas.message.AtlasMessages;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.storage.AtlasRepository;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PortalServiceTest {
    private static AtlasConfig shippedConfig() throws Exception {
        try (var input = PortalServiceTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            yaml.set("database.host", "sentinel-db-host.invalid");
            yaml.set("database.name", "sentinel-database-name");
            yaml.set("database.username", "sentinel-database-user");
            yaml.set("database.password", "sentinel-password");
            return AtlasConfig.read(yaml);
        }
    }

    @Test void persistedThirdNodeTargetIsRefusedBeforeTicketOrProxySendForRegularAndOpPlayers()
            throws Exception {
        var repository = mock(AtlasRepository.class);
        var messages = mock(AtlasMessages.class);
        var service = new PortalService(null, repository, null, shippedConfig(), null, messages);
        var malformed = new Portal("legacy-third", "minecraft:overworld", 0, 60, 0, 1, 63, 0,
                new PortalTarget(PortalTarget.Kind.SERVER, Optional.of("APAC"), "atlas:arena", Optional.empty()),
                2500, Optional.empty(), Optional.empty(), false, Optional.empty());

        for (boolean operator : List.of(false, true)) {
            Player player = mock(Player.class);
            when(player.isOp()).thenReturn(operator);
            assertEquals(PortalService.Travel.INVALID_TARGET,
                    service.travel(player, malformed).get(1, TimeUnit.SECONDS));
            verify(player, never()).getScheduler();
            verify(player, never()).sendPluginMessage(any(Plugin.class), eq("BungeeCord"), any(byte[].class));
        }

        verifyNoInteractions(repository);
        verify(messages, times(1)).warn("portal.invalid-region");
    }

    @Test void simultaneousEditsReadTheLastPublishedPortalInsteadOfOverwritingEachOther() throws Exception {
        var stored = new AtomicReference<>(new Portal("gate", "minecraft:overworld", 0, 60, 0, 1, 63, 0,
                PortalTarget.spawn("atlas:arena"), 2500, Optional.empty(), Optional.empty(), false, Optional.empty()));
        var repository = mock(AtlasRepository.class);
        when(repository.loadPortals()).thenAnswer(ignored -> List.of(stored.get()));
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Portal value = call.getArgument(0);
            if (value.cooldownMillis() == 1000 && !value.restricted()) {
                saving.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            stored.set(value);
            return null;
        }).when(repository).savePortal(any());
        var plugin = mock(Plugin.class);
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class));
        try (var workers = new AtlasWorkers(2, 8)) {
            var service = new PortalService(plugin, repository, workers, null, null, null);
            service.reload().get(5, TimeUnit.SECONDS);
            var cooldown = service.update("gate", portal -> portal.withCooldown(1000));
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            var restricted = service.update("gate", portal -> portal.withRestricted(true));
            release.countDown();
            cooldown.get(5, TimeUnit.SECONDS);
            restricted.get(5, TimeUnit.SECONDS);
            assertEquals(1000, stored.get().cooldownMillis());
            assertTrue(stored.get().restricted());
            assertEquals(stored.get(), service.index().named("gate").orElseThrow());
        } finally { release.countDown(); }
    }
}
