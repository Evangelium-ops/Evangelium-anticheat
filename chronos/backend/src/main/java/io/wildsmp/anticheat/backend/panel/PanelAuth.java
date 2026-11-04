package io.wildsmp.anticheat.backend.panel;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Single-shared-password panel auth: PBKDF2 (JDK-native, no new dependency) for the password
 * check, an HMAC-signed self-verifying session cookie (no server-side session store needed) for
 * everything after login - same overall scheme as the proven ModSync panel, recreated
 * independently rather than reused.
 */
public final class PanelAuth {

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final long SESSION_LIFETIME_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final byte[] salt;
    private final byte[] expectedHash;
    private final SecretKeySpec hmacKey;

    public PanelAuth(String plaintextPassword, String hmacSecret) {
        this.salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        this.expectedHash = pbkdf2(plaintextPassword, salt);
        this.hmacKey = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public boolean checkPassword(String attempt) {
        byte[] attemptHash = pbkdf2(attempt, salt);
        return java.security.MessageDigest.isEqual(attemptHash, expectedHash);
    }

    public String createSessionCookie() {
        long issuedAt = System.currentTimeMillis();
        String body = Long.toString(issuedAt);
        String bodyB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8));
        String signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(bodyB64));
        return bodyB64 + "." + signatureB64;
    }

    public boolean verifySessionCookie(String cookieValue) {
        int dot = cookieValue.indexOf('.');
        if (dot < 0) {
            return false;
        }
        String bodyB64 = cookieValue.substring(0, dot);
        String signatureB64 = cookieValue.substring(dot + 1);
        byte[] expectedSignature;
        byte[] actualSignature;
        long issuedAt;
        try {
            expectedSignature = hmac(bodyB64);
            actualSignature = Base64.getUrlDecoder().decode(signatureB64);
            issuedAt = Long.parseLong(new String(Base64.getUrlDecoder().decode(bodyB64), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false; // malformed base64/number - not a valid cookie, not a crash
        }
        if (!java.security.MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return false;
        }
        return System.currentTimeMillis() - issuedAt <= SESSION_LIFETIME_MILLIS;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 failed", e);
        }
    }
}
