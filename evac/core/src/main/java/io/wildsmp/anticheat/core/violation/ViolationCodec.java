package io.wildsmp.anticheat.core.violation;

import io.wildsmp.anticheat.core.check.CheckCategory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * Pure-JDK wire format for a {@link Violation}, used to carry it over Velocity plugin messaging
 * between a backend server and the proxy. Deliberately not Bukkit/Velocity-specific so it can
 * live in {@code core} and be identical on both sides of the wire.
 */
public final class ViolationCodec {

    private ViolationCodec() {
    }

    public static byte[] encode(Violation v) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeLong(v.playerId().getMostSignificantBits());
            out.writeLong(v.playerId().getLeastSignificantBits());
            out.writeUTF(v.playerName());
            out.writeUTF(v.checkName());
            out.writeUTF(v.category().name());
            out.writeDouble(v.vl());
            out.writeDouble(v.threshold());
            out.writeUTF(v.details() == null ? "" : v.details());
            out.writeLong(v.timestampMillis());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baos.toByteArray();
    }

    public static Violation decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            long msb = in.readLong();
            long lsb = in.readLong();
            UUID playerId = new UUID(msb, lsb);
            String playerName = in.readUTF();
            String checkName = in.readUTF();
            CheckCategory category = CheckCategory.valueOf(in.readUTF());
            double vl = in.readDouble();
            double threshold = in.readDouble();
            String details = in.readUTF();
            long timestamp = in.readLong();
            return new Violation(playerId, playerName, checkName, category, vl, threshold, details, timestamp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
