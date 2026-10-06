package nl.pixelretreat.atlas.message;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;

/** Bundled console-only failure text, loaded before world ticks; no player rendering fallback. */
public final class BootstrapMessages {
    private BootstrapMessages() { }

    /** Uses the same authored key and refuses a missing or malformed bundled template. */
    public static String startupFailure(InputStream bundledCatalog) throws IOException {
        if (bundledCatalog == null) throw new IOException("Bundled Atlas catalog missing");
        try (var reader = new InputStreamReader(bundledCatalog, StandardCharsets.UTF_8)) {
            var yaml = YamlConfiguration.loadConfiguration(reader);
            String text = yaml.getString("startup.failed");
            if (text == null || text.isBlank()) throw new IOException("Bundled startup.failed missing");
            String plain = text.replaceAll("</?#[0-9a-fA-F]{6}>|</?white>", "");
            if (plain.indexOf('<') >= 0 || plain.indexOf('>') >= 0)
                throw new IOException("Invalid bundled startup.failed template");
            return plain;
        }
    }
}
