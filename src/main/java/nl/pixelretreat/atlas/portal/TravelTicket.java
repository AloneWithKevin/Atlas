package nl.pixelretreat.atlas.portal;

import java.util.Optional;
import java.util.UUID;
import nl.pixelretreat.atlas.world.SpawnPoint;

/** A cross-server portal trip waiting for the player to arrive on this server. */
public record TravelTicket(UUID id, UUID player, String portal, String world, Optional<SpawnPoint> point) { }
