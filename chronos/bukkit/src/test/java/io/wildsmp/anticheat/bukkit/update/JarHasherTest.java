package io.wildsmp.anticheat.bukkit.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JarHasherTest {

    @Test
    void computesKnownSha256OfEmptyFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        // Well-known SHA-256 of zero bytes.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", JarHasher.sha256Hex(file));
    }

    @Test
    void computesKnownSha256OfAsciiContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        // Well-known SHA-256 of the ASCII string "hello".
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", JarHasher.sha256Hex(file));
    }
}
