package nl.pixelretreat.atlas.message;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BootstrapMessagesTest {
    @Test void bundledConsoleFailureUsesCatalogTextWithoutColorCodesOrDiagnostics() throws Exception {
        String text = BootstrapMessages.startupFailure(getClass().getResourceAsStream("/messages.yml"));
        assertEquals("Atlas could not start.", text);
        assertFalse(text.contains("<"));
        assertFalse(text.contains("startup.failed"));
    }

    @Test void aBrokenBundleNeverInventsAReplacementMessage() {
        assertThrows(IOException.class, () -> BootstrapMessages.startupFailure(null));
        assertThrows(IOException.class, () -> BootstrapMessages.startupFailure(new ByteArrayInputStream(
                "other: value\n".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IOException.class, () -> BootstrapMessages.startupFailure(new ByteArrayInputStream(
                "startup:\n  failed: '<password>'\n".getBytes(StandardCharsets.UTF_8))));
    }
}
