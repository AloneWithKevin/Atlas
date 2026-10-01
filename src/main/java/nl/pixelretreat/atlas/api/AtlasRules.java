package nl.pixelretreat.atlas.api;

import org.bukkit.World;
import org.bukkit.entity.EntityType;

/**
 * Read-only view of Atlas world rules, published through Bukkit's services manager once Atlas is
 * ready. Every method is thread-safe and reads an immutable snapshot. While Atlas has no rules
 * loaded the answers are restrictive: flags are off and spawns are refused.
 */
public interface AtlasRules {
    /** Who asks for a creature to appear. */
    enum SpawnOrigin {
        /** Natural spawning, spawners, eggs, breeding, commands and other game mechanics. */
        GAME,
        /** A plugin spawning the creature through the API, such as a released capsule or a custom spawner. */
        PLUGIN
    }

    /** Whether Atlas has loaded its rules. */
    boolean ready();

    /** The effective value of a flag in a world. */
    boolean flagEnabled(World world, AtlasFlag flag);

    /**
     * Whether a creature of this type may appear in the world. Hostile and friendly creatures follow
     * {@code hostile-mobs}/{@code friendly-mobs} for game spawns and the {@code plugin-*} flags for
     * plugin spawns; other living entities are always allowed.
     */
    boolean allowsSpawn(World world, EntityType type, SpawnOrigin origin);
}
