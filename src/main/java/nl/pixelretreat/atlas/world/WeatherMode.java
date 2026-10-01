package nl.pixelretreat.atlas.world;

import java.util.Locale;
import java.util.Optional;

/** A weather state staff can put a world in. */
public enum WeatherMode {
    CLEAR, RAIN, THUNDER;

    /** Parses {@code clear}, {@code rain}, {@code thunder} or {@code storm}. */
    public static Optional<WeatherMode> fromKey(String input) {
        if (input == null) return Optional.empty();
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "clear" -> Optional.of(CLEAR);
            case "rain" -> Optional.of(RAIN);
            case "thunder", "storm" -> Optional.of(THUNDER);
            default -> Optional.empty();
        };
    }
}
