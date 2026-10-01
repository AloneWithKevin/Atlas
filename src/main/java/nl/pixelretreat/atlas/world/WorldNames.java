package nl.pixelretreat.atlas.world;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps the short world names staff type to dimension keys. The three vanilla dimensions keep their
 * {@code minecraft:} keys; every Atlas world lives under the {@code atlas:} namespace.
 */
public final class WorldNames {
    public static final String NAMESPACE = "atlas";
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";
    private static final Set<String> BUILT_IN = Set.of(OVERWORLD, NETHER, END);
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,48}");

    private WorldNames() { }

    /** Resolves {@code overworld}, {@code atlas:arena} or {@code arena} to a dimension key. */
    public static Optional<String> key(String input) {
        if (input == null) return Optional.empty();
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) value = value.substring("minecraft:".length());
        else if (value.startsWith(NAMESPACE + ":")) {
            String name = value.substring(NAMESPACE.length() + 1);
            return isNewWorldName(name) ? Optional.of(NAMESPACE + ":" + name) : Optional.empty();
        }
        return switch (value) {
            case "overworld" -> Optional.of(OVERWORLD);
            case "the_nether", "nether" -> Optional.of(NETHER);
            case "the_end", "end" -> Optional.of(END);
            default -> isNewWorldName(value) ? Optional.of(NAMESPACE + ":" + value) : Optional.empty();
        };
    }

    /** Whether a name may be used for a new Atlas world. */
    public static boolean isNewWorldName(String name) {
        return name != null && NAME.matcher(name).matches()
                && !Set.of("overworld", "the_nether", "nether", "the_end", "end").contains(name);
    }

    /** Whether the key belongs to one of the three vanilla dimensions. */
    public static boolean builtIn(String key) { return BUILT_IN.contains(key); }

    /** The short name staff see: {@code overworld} or {@code arena}. */
    public static String shortName(String key) {
        int separator = key.indexOf(':');
        return separator < 0 ? key : key.substring(separator + 1);
    }

    /** The namespace part of a key. */
    public static String namespace(String key) { return key.substring(0, key.indexOf(':')); }
}
