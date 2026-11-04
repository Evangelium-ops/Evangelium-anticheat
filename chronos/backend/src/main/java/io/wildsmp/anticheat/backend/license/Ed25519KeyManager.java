package io.wildsmp.anticheat.backend.license;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Loads an Ed25519 private key (PKCS8 PEM, e.g. generated via {@code openssl genpkey -algorithm
 * ed25519}) and signs the canonical strings backend uses for approved/revoked frames. Zero new
 * crypto dependency - {@code java.security}'s {@code "Ed25519"} algorithm has shipped since JDK
 * 15, this project targets 21.
 */
public final class Ed25519KeyManager {

    private final PrivateKey privateKey;

    public Ed25519KeyManager(Path privateKeyPemPath) {
        String pem;
        try {
            pem = Files.readString(privateKeyPemPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Ed25519 private key at " + privateKeyPemPath, e);
        }
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to load Ed25519 private key from " + privateKeyPemPath, e);
        }
    }

    public byte[] sign(String canonicalString) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(canonicalString.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign canonical string", e);
        }
    }

    public static String canonicalString(String type, String code, long seq, long issuedAtMillis) {
        return type + "\n" + code + "\n" + seq + "\n" + issuedAtMillis;
    }
}
