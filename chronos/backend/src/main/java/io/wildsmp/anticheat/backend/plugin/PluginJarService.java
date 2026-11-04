package io.wildsmp.anticheat.backend.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hashes and serves the one plugin jar this backend is configured to distribute to connected
 * bukkit agents - baked into the backend's own Docker image (see deploy/k8s/README.md's "publish
 * a new bukkit build" section), read and hashed once at construction. If the configured path
 * doesn't exist, this is simply inert - {@link #hasJar()} is false and callers treat that as "no
 * update ever available," no special-casing needed elsewhere.
 */
public final class PluginJarService {

    private final boolean hasJar;
    private final String sha256;
    private final long sizeBytes;
    private final byte[] bytes;

    public PluginJarService(Path jarPath) {
        if (!Files.isRegularFile(jarPath)) {
            this.hasJar = false;
            this.sha256 = "";
            this.sizeBytes = 0L;
            this.bytes = new byte[0];
            return;
        }
        try {
            this.bytes = Files.readAllBytes(jarPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read configured plugin jar at " + jarPath, e);
        }
        this.hasJar = true;
        this.sizeBytes = bytes.length;
        this.sha256 = sha256Hex(bytes);
    }

    public boolean hasJar() {
        return hasJar;
    }

    public String sha256() {
        return sha256;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public byte[] readAllBytes() {
        return bytes;
    }

    private static String sha256Hex(byte[] data) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] hash = digest.digest(data);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
