package io.wildsmp.anticheat.backend.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginJarServiceTest {

    @Test
    void hasJarIsFalseWhenFileDoesNotExist(@TempDir Path dir) {
        PluginJarService service = new PluginJarService(dir.resolve("does-not-exist.jar"));

        assertFalse(service.hasJar());
        assertEquals("", service.sha256());
        assertEquals(0L, service.sizeBytes());
    }

    @Test
    void computesRealSha256AndSizeOfAnExistingFile(@TempDir Path dir) throws Exception {
        byte[] content = "hello anticheat jar bytes".getBytes(StandardCharsets.UTF_8);
        Path jar = dir.resolve("anticheat-bukkit.jar");
        Files.write(jar, content);

        PluginJarService service = new PluginJarService(jar);

        assertTrue(service.hasJar());
        assertEquals(sha256Hex(content), service.sha256());
        assertEquals(content.length, service.sizeBytes());
        assertArrayEquals(content, service.readAllBytes());
    }

    private static String sha256Hex(byte[] data) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
