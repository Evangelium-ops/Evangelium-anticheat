package io.wildsmp.anticheat.backend.license;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed approval state for connecting Minecraft servers, on a path expected to be a
 * PersistentVolumeClaim mount so it survives a backend pod restart/reschedule. Every write goes
 * through a single synchronized connection - approval traffic is low-volume (one write per
 * operator action or per reconnecting server), so this isn't a throughput concern.
 */
public final class LicenseStore implements AutoCloseable {

    public enum State { PENDING, APPROVED, DECLINED, DISABLED }

    public record ServerRecord(String code, State state, long seq, String instanceId, String boundIp,
                                String serverName, String mcVersion, long lastSeenMillis) {
    }

    private static final String CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford base32, no I L O U
    private final SecureRandom random = new SecureRandom();
    private final Connection connection;

    public LicenseStore(Path dbPath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (var st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS servers (
                            code TEXT PRIMARY KEY,
                            state TEXT NOT NULL,
                            seq INTEGER NOT NULL,
                            instance_id TEXT,
                            bound_ip TEXT,
                            server_name TEXT,
                            mc_version TEXT,
                            last_seen_millis INTEGER
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS audit (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            code TEXT NOT NULL,
                            event TEXT NOT NULL,
                            timestamp_millis INTEGER NOT NULL
                        )""");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open license store at " + dbPath, e);
        }
    }

    public synchronized ServerRecord mint(String serverName, String mcVersion) {
        String code = generateCode();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO servers (code, state, seq, server_name, mc_version, last_seen_millis) VALUES (?, ?, 0, ?, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, State.PENDING.name());
            ps.setString(3, serverName);
            ps.setString(4, mcVersion);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mint a server code", e);
        }
        appendAudit(code, "minted");
        return find(code).orElseThrow();
    }

    public synchronized Optional<ServerRecord> find(String code) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM servers WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(toRecord(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to look up server code " + code, e);
        }
    }

    public synchronized List<ServerRecord> listAll() {
        List<ServerRecord> result = new ArrayList<>();
        try (var st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM servers")) {
            while (rs.next()) {
                result.add(toRecord(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list servers", e);
        }
        return result;
    }

    /**
     * Atomically bumps and returns the new seq for {@code code}. Every signed frame actually sent
     * to a client - an admin approval/decline, or the "approved" frame re-pushed on every routine
     * reconnect's Hello handshake for an already-approved server - needs a fresh, strictly
     * increasing seq of its own: the client's replay protection rejects a repeated value outright,
     * which otherwise permanently blocks re-authorization on reconnect (a repeated seq looks
     * identical to a replayed old frame) until the whole plugin JVM restarts and resets its
     * in-memory "last seen seq" back to nothing.
     */
    public synchronized long nextSeq(String code) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE servers SET seq = seq + 1 WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bump seq for " + code, e);
        }
        return find(code).orElseThrow().seq();
    }

    public synchronized void updateState(String code, State newState) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE servers SET state = ?, seq = seq + 1 WHERE code = ?")) {
            ps.setString(1, newState.name());
            ps.setString(2, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update state for " + code, e);
        }
        appendAudit(code, newState.name().toLowerCase());
    }

    /** Binds on first use; returns true if the binding now matches {@code instanceId} (fresh or already-matching), false if it's bound to someone else. */
    public synchronized boolean bindInstance(String code, String instanceId, String remoteIp) {
        Optional<ServerRecord> existing = find(code);
        if (existing.isEmpty()) {
            return false;
        }
        String currentInstanceId = existing.get().instanceId();
        if (currentInstanceId == null || currentInstanceId.isEmpty()) {
            setBinding(code, instanceId, remoteIp);
            appendAudit(code, "bound");
            return true;
        }
        if (!currentInstanceId.equals(instanceId)) {
            appendAudit(code, "blocked");
            return false;
        }
        if (!remoteIp.equals(existing.get().boundIp())) {
            updateBoundIp(code, remoteIp);
            appendAudit(code, "ip-changed");
        }
        return true;
    }

    public synchronized void updateBoundIp(String code, String newIp) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE servers SET bound_ip = ? WHERE code = ?")) {
            ps.setString(1, newIp);
            ps.setString(2, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update bound IP for " + code, e);
        }
    }

    public synchronized void touch(String code, long nowMillis) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE servers SET last_seen_millis = ? WHERE code = ?")) {
            ps.setLong(1, nowMillis);
            ps.setString(2, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to touch " + code, e);
        }
    }

    public synchronized void appendAudit(String code, String event) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO audit (code, event, timestamp_millis) VALUES (?, ?, ?)")) {
            ps.setString(1, code);
            ps.setString(2, event);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append audit for " + code, e);
        }
    }

    public synchronized List<String> auditFor(String code) {
        List<String> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT event FROM audit WHERE code = ? ORDER BY id ASC")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(rs.getString("event"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read audit for " + code, e);
        }
        return events;
    }

    private void setBinding(String code, String instanceId, String remoteIp) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE servers SET instance_id = ?, bound_ip = ? WHERE code = ?")) {
            ps.setString(1, instanceId);
            ps.setString(2, remoteIp);
            ps.setString(3, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bind instance for " + code, e);
        }
    }

    private static ServerRecord toRecord(ResultSet rs) throws SQLException {
        return new ServerRecord(
                rs.getString("code"),
                State.valueOf(rs.getString("state")),
                rs.getLong("seq"),
                rs.getString("instance_id"),
                rs.getString("bound_ip"),
                rs.getString("server_name"),
                rs.getString("mc_version"),
                rs.getLong("last_seen_millis"));
    }

    /** Closes the underlying SQLite connection - important on Windows, where an open native file handle blocks deleting/replacing the DB file even after the JVM believes it's done with it. */
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close license store connection", e);
        }
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder("ANTICHEAT-");
        for (int group = 0; group < 3; group++) {
            if (group > 0) {
                sb.append('-');
            }
            for (int i = 0; i < 4; i++) {
                sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
        }
        return sb.toString();
    }
}
