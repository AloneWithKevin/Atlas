package nl.pixelretreat.atlas.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Per-world on/off rules. Every world can override each flag; others use the configured default. */
public enum AtlasFlag {
    HOSTILE_MOBS("hostile-mobs"),
    FRIENDLY_MOBS("friendly-mobs"),
    PLUGIN_HOSTILE_MOBS("plugin-hostile-mobs"),
    PLUGIN_FRIENDLY_MOBS("plugin-friendly-mobs"),
    TNT_DAMAGE("tnt-damage"),
    CRYSTAL_DAMAGE("crystal-damage"),
    FIRE_DAMAGE("fire-damage"),
    EXPLOSION_DAMAGE("explosion-damage"),
    PVP("pvp"),
    FALL_DAMAGE("fall-damage"),
    HUNGER("hunger"),
    DROWNING("drowning"),
    BLOCK_BREAK("block-break"),
    BLOCK_PLACE("block-place"),
    LEAF_DECAY("leaf-decay"),
    CROP_TRAMPLING("crop-trampling"),
    ITEM_DROP("item-drop"),
    ITEM_PICKUP("item-pickup"),
    WEATHER("weather"),
    TIME_CYCLE("time-cycle"),
    TIME_SKIP("time-skip"),
    MOB_GRIEFING("mob-griefing"),
    KEEP_INVENTORY("keep-inventory"),
    PORTALS("portals");

    private final String key;

    AtlasFlag(String key) { this.key = key; }

    /** The stable lowercase key used in commands, config and the database. */
    public String key() { return key; }

    /** Resolves a key such as {@code fire-damage}; underscores are accepted as hyphens. */
    public static Optional<AtlasFlag> fromKey(String input) {
        if (input == null) return Optional.empty();
        String normalized = input.toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(flag -> flag.key.equals(normalized)).findFirst();
    }
}
