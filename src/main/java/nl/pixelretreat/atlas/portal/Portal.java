package nl.pixelretreat.atlas.portal;

import java.util.Optional;

/**
 * A cuboid that sends players who walk into it to its target.
 *
 * @param world      key of the world the portal stands in
 * @param restricted whether {@code atlas.portal.<name>} is required in addition to {@code atlas.portal.use}
 * @param sound      namespaced sound key played to the traveller, if any
 * @param particle   Bukkit particle name shown to the traveller, if any
 * @param fillBlock  Bukkit block material that fills the portal, if any
 */
public record Portal(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                     PortalTarget target, int cooldownMillis, Optional<String> sound, Optional<String> particle,
                     boolean restricted, Optional<String> fillBlock) {

    /** Whether a block position lies inside the portal. */
    public boolean contains(String worldKey, int x, int y, int z) {
        return world.equals(worldKey) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Number of blocks the portal covers. */
    public long volume() {
        return ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
    }

    /** The permission node of a restricted portal. */
    public String permission() { return "atlas.portal." + name; }

    public Portal withTarget(PortalTarget value) {
        return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, value, cooldownMillis, sound, particle, restricted, fillBlock);
    }

    public Portal withCooldown(int value) {
        return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, target, value, sound, particle, restricted, fillBlock);
    }

    public Portal withSound(Optional<String> value) {
        return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, target, cooldownMillis, value, particle, restricted, fillBlock);
    }

    public Portal withParticle(Optional<String> value) {
        return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, target, cooldownMillis, sound, value, restricted, fillBlock);
    }

    public Portal withRestricted(boolean value) {
        return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, target, cooldownMillis, sound, particle, value, fillBlock);
    }

    public Portal withFill(Optional<String> value) {
        return new Portal(name, world, minX, minY, minZ, maxX, maxY, maxZ, target, cooldownMillis, sound, particle, restricted, value);
    }
}
