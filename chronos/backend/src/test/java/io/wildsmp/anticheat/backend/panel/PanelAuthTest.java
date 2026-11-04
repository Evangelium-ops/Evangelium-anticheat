package io.wildsmp.anticheat.backend.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelAuthTest {

    @Test
    void correctPasswordChecksOut() {
        PanelAuth auth = new PanelAuth("correct-horse-battery-staple", "hmac-secret-at-least-16-chars");

        assertTrue(auth.checkPassword("correct-horse-battery-staple"));
    }

    @Test
    void wrongPasswordFails() {
        PanelAuth auth = new PanelAuth("correct-horse-battery-staple", "hmac-secret-at-least-16-chars");

        assertFalse(auth.checkPassword("wrong-password"));
    }

    @Test
    void aFreshlyCreatedSessionCookieVerifies() {
        PanelAuth auth = new PanelAuth("password", "hmac-secret-at-least-16-chars");

        String cookie = auth.createSessionCookie();

        assertTrue(auth.verifySessionCookie(cookie));
    }

    @Test
    void aTamperedCookieFailsVerification() {
        PanelAuth auth = new PanelAuth("password", "hmac-secret-at-least-16-chars");
        String cookie = auth.createSessionCookie();

        String tampered = cookie.substring(0, cookie.length() - 1) + (cookie.endsWith("a") ? "b" : "a");

        assertFalse(auth.verifySessionCookie(tampered));
    }

    @Test
    void aCookieSignedByADifferentSecretFailsVerification() {
        PanelAuth issuer = new PanelAuth("password", "hmac-secret-at-least-16-chars-a");
        PanelAuth verifier = new PanelAuth("password", "hmac-secret-at-least-16-chars-b");

        String cookie = issuer.createSessionCookie();

        assertFalse(verifier.verifySessionCookie(cookie));
    }

    @Test
    void garbageCookieValueFailsVerificationRatherThanThrowing() {
        PanelAuth auth = new PanelAuth("password", "hmac-secret-at-least-16-chars");

        assertFalse(auth.verifySessionCookie("not-a-real-cookie"));
    }
}
