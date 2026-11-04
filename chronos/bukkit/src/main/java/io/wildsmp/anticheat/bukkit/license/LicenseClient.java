package io.wildsmp.anticheat.bukkit.license;

import io.wildsmp.anticheat.protocol.Hello;
import io.wildsmp.anticheat.protocol.SignedMessage;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persists a random instanceId once (license.yml, alongside config.yml) and verifies every
 * approved/revoked frame the backend sends - the same four checks ModSync's own mod-side
 * verification runs (signature, code match, strictly-increasing seq, freshness window),
 * recreated independently.
 */
public final class LicenseClient {

    private static final long FRESHNESS_BEFORE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long FRESHNESS_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final Path licenseYmlPath;
    private final PublicKey backendPublicKey;
    private final String instanceId;
    private final AtomicReference<String> code = new AtomicReference<>("");
    private final AtomicLong lastSeenSeq = new AtomicLong(-1);

    public LicenseClient(Path licenseYmlPath, PublicKey backendPublicKey) {
        this.licenseYmlPath = licenseYmlPath;
        this.backendPublicKey = backendPublicKey;
        Map<String, Object> loaded = load();
        String existingInstanceId = (String) loaded.get("instance-id");
        this.instanceId = existingInstanceId != null ? existingInstanceId : UUID.randomUUID().toString();
        String existingCode = (String) loaded.get("code");
        if (existingCode != null) {
            code.set(existingCode);
        }
        save();
    }

    public String code() {
        return code.get();
    }

    public Hello buildHello(String serverName, String mcVersion) {
        return Hello.newBuilder()
                .setCode(code.get())
                .setInstanceId(instanceId)
                .setModVersion("1.0.0-SNAPSHOT")
                .setMcVersion(mcVersion)
                .setServerName(serverName)
                .build();
    }

    public void onAssigned(String newCode) {
        code.set(newCode);
        save();
    }

    /** Returns true if this frame is a valid, fresh "approved" this install should now trust. */
    public synchronized boolean acceptIfApproved(SignedMessage frame) {
        if (!"approved".equals(frame.getType())) return false;
        if (!verify(frame, backendPublicKey, code.get(), lastSeenSeq.get())) {
            return false;
        }
        lastSeenSeq.set(frame.getSeq());
        return "approved".equals(frame.getType());
    }

    /** Returns true if this frame is a valid, fresh "revoked" - caller should self-delete. */
    public synchronized boolean acceptIfRevoked(SignedMessage frame) {
        if (!"revoked".equals(frame.getType())) return false;
        if (!verify(frame, backendPublicKey, code.get(), lastSeenSeq.get())) {
            return false;
        }
        lastSeenSeq.set(frame.getSeq());
        return "revoked".equals(frame.getType());
    }

    public static boolean verify(SignedMessage frame, PublicKey backendPublicKey, String expectedCode, long lastSeenSeq) {
        if (!frame.getCode().equals(expectedCode)) {
            return false;
        }
        if (frame.getSeq() <= lastSeenSeq) {
            return false;
        }
        long now = System.currentTimeMillis();
        long issuedAt = frame.getIssuedAtMillis();
        if (issuedAt < now - FRESHNESS_BEFORE_MILLIS || issuedAt > now + FRESHNESS_AFTER_MILLIS) {
            return false;
        }
        String canonical = frame.getType() + "\n" + frame.getCode() + "\n" + frame.getSeq() + "\n" + frame.getIssuedAtMillis();
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(backendPublicKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(frame.getSignature().toByteArray());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        if (!Files.isRegularFile(licenseYmlPath)) {
            return new LinkedHashMap<>();
        }
        try {
            Object loaded = new Yaml().load(Files.newBufferedReader(licenseYmlPath, StandardCharsets.UTF_8));
            return loaded instanceof Map ? (Map<String, Object>) loaded : new LinkedHashMap<>();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + licenseYmlPath, e);
        }
    }

    private void save() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instance-id", instanceId);
        data.put("code", code.get());
        try {
            Files.writeString(licenseYmlPath, new Yaml().dump(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + licenseYmlPath, e);
        }
    }
}
