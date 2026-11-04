package io.wildsmp.anticheat.backend.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519KeyManagerTest {

    @Test
    void canonicalStringMatchesTheDocumentedFormat() {
        String result = Ed25519KeyManager.canonicalString("approved", "ANTICHEAT-AAAA-BBBB-CCCC", 3L, 1700000000000L);

        assertEquals("approved\nANTICHEAT-AAAA-BBBB-CCCC\n3\n1700000000000", result);
    }

    @Test
    void signsWithAKeyThatIndependentlyVerifiesAsEd25519(@TempDir Path dir) throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path pemFile = dir.resolve("private.pem");
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(pemFile, pem);

        Ed25519KeyManager manager = new Ed25519KeyManager(pemFile);
        String canonical = Ed25519KeyManager.canonicalString("approved", "ANTICHEAT-AAAA-BBBB-CCCC", 1L, 1700000000000L);
        byte[] signature = manager.sign(canonical);

        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(keyPair.getPublic().getEncoded()));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(verifier.verify(signature));
    }
}
