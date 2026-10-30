package nl.pixelretreat.atlas.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import nl.pixelretreat.atlas.portal.PortalTarget;
import nl.pixelretreat.campfire.api.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class AtlasCompoundMessagesTest {
    @Test void playerDestinationRemainsAReferenceUntilTheRecipientsCompoundDelivery() {
        Plugin owner = mock(Plugin.class); when(owner.isEnabled()).thenReturn(true);
        Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        MessageCatalog catalog = mock(MessageCatalog.class);
        CampfireDelivery delivery = mock(CampfireDelivery.class);
        when(delivery.sendCompound(eq(owner), eq(player), anyString(), anyMap(), anyMap(), anyMap(), any()))
            .thenReturn(CompletableFuture.completedFuture(DeliveryResult.SENT));
        var messages = new AtlasMessages(owner, catalog, delivery);
        clearInvocations(catalog);
        var reference = messages.destinationReference(PortalTarget.spawn("atlas:<red>arena</red>"));
        messages.sendCompound(player, "portal.list-entry", Map.of("portal", "gate", "world", "origin"), Map.of("target", reference));
        verify(delivery).sendCompound(owner, player, "portal.list-entry", Map.of("portal", "gate", "world", "origin"),
            Map.of(), Map.of("target", new KeyReference("destination.local-spawn", Map.of("world", "<red>arena</red>"), Map.of())),
            DeliveryOptions.serverChat());
        verifyNoInteractions(catalog);
    }

    @Test void nativeRecipientRenderingUsesUuidWithoutOnlinePlayerLookup() throws Exception {
        Plugin owner = mock(Plugin.class); var catalog = mock(MessageCatalog.class);
        var delivery = mock(CampfireDelivery.class); UUID recipient = UUID.randomUUID();
        var values = Map.of("world", "arena");
        when(delivery.render(owner, recipient, "world.closed-entry", values, Map.of()))
            .thenReturn(CompletableFuture.completedFuture(Component.text("Wereld arena is gesloten.")));
        var messages = new AtlasMessages(owner, catalog, delivery);
        assertEquals(Component.text("Wereld arena is gesloten."), messages.render(recipient, "world.closed-entry", values)
            .toCompletableFuture().get());
        verifyNoInteractions(owner);
    }
}
