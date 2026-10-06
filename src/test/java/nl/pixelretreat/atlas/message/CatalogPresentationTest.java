package nl.pixelretreat.atlas.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import nl.pixelretreat.atlas.portal.Portal;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.atlas.world.SpawnPoint;
import nl.pixelretreat.campfire.api.CampfireDelivery;
import nl.pixelretreat.campfire.catalog.CatalogLifetime;
import nl.pixelretreat.campfire.catalog.LoadedMessageCatalog;
import nl.pixelretreat.campfire.catalog.MessageFileLoader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual Campfire rendering proves target formatting, configurable bindings and safe diagnostics. */
class CatalogPresentationTest {
    @TempDir Path directory;

    @Test void shippedDestinationsRetainAllFourFormatsAndTruncatedIntegerCoordinates() throws Exception {
        var messages = messages(Map.of(), mock(Logger.class));
        var point = Optional.of(new SpawnPoint(-3.8, 64.9, 9.1, 0, 0));
        assertEquals("arena spawn", messages.destination(PortalTarget.spawn("atlas:arena")));
        assertEquals("arena -3,64,9", messages.destination(new PortalTarget(
                PortalTarget.Kind.LOCATION, Optional.empty(), "atlas:arena", point)));
        assertEquals("NA:arena spawn", messages.destination(new PortalTarget(
                PortalTarget.Kind.SERVER, Optional.of("NA"), "atlas:arena", Optional.empty())));
        assertEquals("NA:arena -3,64,9", messages.destination(new PortalTarget(
                PortalTarget.Kind.SERVER, Optional.of("NA"), "atlas:arena", point)));
        assertEquals("-4,60,-9 -> 3,70,5", messages.bounds(portal()));
    }

    @Test void overridesDriveEveryNewTemplateWithoutChangingValuesOrInterpretingInput() throws Exception {
        var messages = messages(Map.of(
                "destination.local-spawn", "Local spawn: <world>",
                "destination.local-location", "<world> at <x>/<y>/<z>",
                "destination.remote-spawn", "<world> spawn on <region>",
                "destination.remote-location", "<region> <world> at <z>/<y>/<x>",
                "destination.bounds", "From <minx>/<miny>/<minz> to <maxx>/<maxy>/<maxz>",
                "operation.delete-reenabled", "Deletion refused: world enabled again."),
                mock(Logger.class));
        var point = Optional.of(new SpawnPoint(-3.8, 64.9, 9.1, 0, 0));
        assertEquals("Local spawn: arena", messages.destination(PortalTarget.spawn("atlas:arena")));
        assertEquals("arena at -3/64/9", messages.destination(new PortalTarget(
                PortalTarget.Kind.LOCATION, Optional.empty(), "atlas:arena", point)));
        assertEquals("arena spawn on NA", messages.destination(new PortalTarget(
                PortalTarget.Kind.SERVER, Optional.of("NA"), "atlas:arena", Optional.empty())));
        assertEquals("NA arena at 9/64/-3", messages.destination(new PortalTarget(
                PortalTarget.Kind.SERVER, Optional.of("NA"), "atlas:arena", point)));
        assertEquals("From -4/60/-9 to 3/70/5", messages.bounds(portal()));
        assertEquals("Deletion refused: world enabled again.", messages.operationDetail("operation.delete-reenabled"));
        assertEquals("UNCERTAIN", messages.operationDetail("UNCERTAIN"));
        assertEquals("diagnostic.travel-ticket-read", messages.operationDetail("diagnostic.travel-ticket-read"),
                "arbitrary diagnostic keys are not resolved as operation meanings");
        String rendered = messages.destination(PortalTarget.spawn("atlas:<red>literal</red>"));
        assertEquals("Local spawn: <red>literal</red>", rendered);
    }

    @Test void keyedWarningsUseOnlyAuthoredTextAndNeverAttachExceptionDetails() throws Exception {
        Logger logger = mock(Logger.class);
        var messages = messages(Map.of(
                "diagnostic.travel-ticket-read", "Unable to read this travel ticket.",
                "diagnostic.travel-ticket-withdraw", "Unable to withdraw this unused ticket."),
                logger);
        messages.warn("diagnostic.travel-ticket-read");
        messages.warn("diagnostic.travel-ticket-withdraw");
        verify(logger).warning("Unable to read this travel ticket.");
        verify(logger).warning("Unable to withdraw this unused ticket.");
        verifyNoMoreInteractions(logger);
    }

    @Test void failedArrivalKeepsPlayerOutcomeAndUsesCatalogDiagnosticInsteadOfCause() {
        var portals = mock(nl.pixelretreat.atlas.service.PortalService.class);
        var player = mock(org.bukkit.entity.Player.class);
        var output = mock(AtlasMessages.class);
        when(portals.arrive(player)).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(
                new java.sql.SQLException("private connection details")));
        var listener = new nl.pixelretreat.atlas.listener.PlayerListener(mock(Plugin.class),
                mock(nl.pixelretreat.atlas.service.RulesService.class), portals,
                mock(nl.pixelretreat.atlas.service.SelectionService.class), output);
        listener.arrived(player);
        verify(output).warn("diagnostic.travel-ticket-read");
        verify(output).send(player, "portal.arrival-failed");
        verifyNoMoreInteractions(output);
    }

    private Portal portal() {
        return new Portal("gate", "atlas:origin", -4, 60, -9, 3, 70, 5,
                PortalTarget.spawn("atlas:arena"), 2500, Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    private AtlasMessages messages(Map<String, String> overrides, Logger logger) throws Exception {
        Path file = directory.resolve("messages.yml");
        try (var input = getClass().getResourceAsStream("/messages.yml")) {
            Files.copy(input, file);
        }
        if (!overrides.isEmpty()) {
            var yaml = YamlConfiguration.loadConfiguration(file.toFile());
            overrides.forEach(yaml::set);
            yaml.save(file.toFile());
        }
        var catalog = new LoadedMessageCatalog("Atlas",
                new MessageFileLoader().load("Atlas", file), new CatalogLifetime());
        var owner = mock(Plugin.class);
        when(owner.getLogger()).thenReturn(logger);
        return new AtlasMessages(owner, catalog, mock(CampfireDelivery.class));
    }
}
