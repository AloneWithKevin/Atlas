package nl.pixelretreat.atlas.world;

import java.util.Locale;
import java.util.Optional;

/** Terrain generators available for new Atlas worlds, with the vanilla dimension type they use. */
public enum WorldGenerator {
    NORMAL("minecraft:overworld"),
    FLAT("minecraft:overworld"),
    VOID("minecraft:overworld"),
    NETHER("minecraft:the_nether"),
    END("minecraft:the_end");

    private final String dimensionType;

    WorldGenerator(String dimensionType) { this.dimensionType = dimensionType; }

    /** The vanilla dimension type (sky, height, clock, nether physics). */
    public String dimensionType() { return dimensionType; }

    /** Lowercase command and database name. */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** Parses a command argument. */
    public static Optional<WorldGenerator> fromKey(String input) {
        if (input == null) return Optional.empty();
        try { return Optional.of(valueOf(input.trim().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException unknown) { return Optional.empty(); }
    }

    /** The generator that matches a vanilla dimension, used when one is the source of a clone. */
    public static WorldGenerator forBuiltIn(String key) {
        return switch (key) {
            case WorldNames.NETHER -> NETHER;
            case WorldNames.END -> END;
            default -> NORMAL;
        };
    }
}
