package io.wildsmp.anticheat.bukkit.license;

import io.wildsmp.anticheat.protocol.SignedMessage;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseClientTest {

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static SignedMessage signed(KeyPair kp, String type, String code, long seq, long issuedAt) throws Exception {
        String canonical = type + "\n" + code + "\n" + seq + "\n" + issuedAt;
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(kp.getPrivate());
        signature.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return SignedMessage.newBuilder().setType(type).setCode(code).setSeq(seq).setIssuedAtMillis(issuedAt)
                .setSignature(com.google.protobuf.ByteString.copyFrom(signature.sign())).build();
    }

    @Test
    void validFreshFrameVerifies() throws Exception {
        KeyPair kp = keyPair();
        SignedMessage frame = signed(kp, "approved", "ANTICHEAT-AAAA-BBBB-CCCC", 1, System.currentTimeMillis());

        assertTrue(LicenseClient.verify(frame, kp.getPublic(), "ANTICHEAT-AAAA-BBBB-CCCC", 0));
    }

    @Test
    void wrongSignatureFails() throws Exception {
        KeyPair real = keyPair();
        KeyPair impostor = keyPair();
        SignedMessage frame = signed(impostor, "approved", "ANTICHEAT-AAAA-BBBB-CCCC", 1, System.currentTimeMillis());

        assertFalse(LicenseClient.verify(frame, real.getPublic(), "ANTICHEAT-AAAA-BBBB-CCCC", 0));
    }

    @Test
    void mismatchedCodeFailsEvenWithAValidSignature() throws Exception {
        KeyPair kp = keyPair();
        SignedMessage frame = signed(kp, "approved", "ANTICHEAT-AAAA-BBBB-CCCC", 1, System.currentTimeMillis());

        assertFalse(LicenseClient.verify(frame, kp.getPublic(), "ANTICHEAT-DIFFERENT-CODE", 0));
    }

    @Test
    void nonIncreasingSeqFails() throws Exception {
        KeyPair kp = keyPair();
        SignedMessage frame = signed(kp, "approved", "ANTICHEAT-AAAA-BBBB-CCCC", 5, System.currentTimeMillis());

        assertFalse(LicenseClient.verify(frame, kp.getPublic(), "ANTICHEAT-AAAA-BBBB-CCCC", 5));
    }

    @Test
    void tooOldFails() throws Exception {
        KeyPair kp = keyPair();
        long sixMinutesAgo = System.currentTimeMillis() - java.util.concurrent.TimeUnit.MINUTES.toMillis(6);
        SignedMessage frame = signed(kp, "approved", "ANTICHEAT-AAAA-BBBB-CCCC", 1, sixMinutesAgo);

        assertFalse(LicenseClient.verify(frame, kp.getPublic(), "ANTICHEAT-AAAA-BBBB-CCCC", 0));
    }

    @Test
    void tooFarInTheFutureFails() throws Exception {
        KeyPair kp = keyPair();
        long twoMinutesFromNow = System.currentTimeMillis() + java.util.concurrent.TimeUnit.MINUTES.toMillis(2);
        SignedMessage frame = signed(kp, "approved", "ANTICHEAT-AAAA-BBBB-CCCC", 1, twoMinutesFromNow);

        assertFalse(LicenseClient.verify(frame, kp.getPublic(), "ANTICHEAT-AAAA-BBBB-CCCC", 0));
    }
    @Test
    void approvalTypeProbeDoesNotConsumeValidRevocation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        KeyPair kp = keyPair();
        LicenseClient client = new LicenseClient(dir.resolve("license.yml"), kp.getPublic());
        client.onAssigned("TEST-CODE");
        SignedMessage revoked = signed(kp, "revoked", "TEST-CODE", 1, System.currentTimeMillis());
        assertFalse(client.acceptIfApproved(revoked));
        assertTrue(client.acceptIfRevoked(revoked));
        assertFalse(client.acceptIfRevoked(revoked), "Replay must still be rejected");
    }
}
