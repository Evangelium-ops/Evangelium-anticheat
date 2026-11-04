package io.knowledge.evangelium;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The key server's answer to a license check: a few key=value lines and an Ed25519 signature over them. The bootstrap only
 * trusts an answer whose signature checks out against the public key built into it, so a fake key server cannot unlock
 * anything. Shared by the key server (signs) and the bootstrap (verifies).
 */
public final class Reply {

    public final Map<String, String> fields;

    public Reply(Map<String, String> fields) {
        this.fields = new LinkedHashMap<>(fields);
    }

    public String get(String key) {
        return fields.get(key);
    }

    /** The text the signature covers: every field except sig, in order. */
    private String body() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!e.getKey().equals("sig")) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    public String encode() {
        return body() + "sig=" + fields.getOrDefault("sig", "") + "\n";
    }

    public static Reply decode(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return new Reply(m);
    }

    public Reply sign(PrivateKey key) throws GeneralSecurityException {
        fields.remove("sig");
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(body().getBytes(StandardCharsets.UTF_8));
        fields.put("sig", Base64.getEncoder().encodeToString(s.sign()));
        return this;
    }

    public boolean verify(PublicKey key) {
        String sig = fields.get("sig");
        if (sig == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(body().getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(sig));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static PublicKey publicKey(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    public static PrivateKey privateKey(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
    }

    /** First 16 hex characters of the key's SHA-256: names a key in replies and logs without revealing it. */
    public static String keyId(String key) {
        return Hash.sha256(key.trim().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
