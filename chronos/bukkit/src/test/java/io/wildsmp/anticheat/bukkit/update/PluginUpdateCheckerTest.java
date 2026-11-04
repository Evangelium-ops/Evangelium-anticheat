package io.wildsmp.anticheat.bukkit.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginUpdateCheckerTest {

    @Test
    void sameSha256MeansNoUpdate() {
        assertFalse(PluginUpdateChecker.isUpdateAvailable("abc123", "abc123"));
    }

    @Test
    void differentSha256MeansUpdateAvailable() {
        assertTrue(PluginUpdateChecker.isUpdateAvailable("abc123", "def456"));
    }

    @Test
    void emptyRemoteSha256MeansNoUpdate() {
        // Empty means backend has no jar configured to serve - see UpdateCheckResponse's doc comment.
        assertFalse(PluginUpdateChecker.isUpdateAvailable("abc123", ""));
    }
}
