package nl.pixelretreat.atlas.message;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import nl.pixelretreat.atlas.service.WorldAdminService;
import org.junit.jupiter.api.Test;

/** Every message key written in the code exists in the contract, so no player sees a missing key. */
class MessageKeyUsageTest {
    private static final Pattern KEY = Pattern.compile(
            "\"((?:common|help|usage|world|info|teleport|spawn|flag|setting|selector|selection|portal|keeploaded|reload|startup|delivery)"
                    + "\\.[a-z.-]+)\"");

    @Test void literalKeysInTheSourcesAreInTheContract() throws Exception {
        var missing = new TreeSet<String>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            // AtlasConfig holds config.yml keys, which share some prefixes with message keys.
            for (Path file : files.filter(path -> path.toString().endsWith(".java")
                    && !path.getFileName().toString().equals("AtlasConfig.java")).toList()) {
                var matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (key.endsWith(".") || key.startsWith("world.error.")) continue;
                    if (!AtlasMessages.CONTRACT.containsKey(key)) missing.add(key + " in " + file.getFileName());
                }
            }
        }
        assertTrue(missing.isEmpty(), "Keys without a contract entry: " + missing);
    }

    @Test void everyLifecycleRefusalHasAMessage() {
        for (WorldAdminService.Result result : WorldAdminService.Result.values()) {
            if (result == WorldAdminService.Result.DONE) continue;
            String key = "world.error." + result.name().toLowerCase(Locale.ROOT).replace('_', '-');
            assertTrue(AtlasMessages.CONTRACT.containsKey(key), key);
            assertEquals(java.util.Set.of("world"), AtlasMessages.CONTRACT.get(key), key);
        }
    }
}
