package io.wildsmp.anticheat.backend.player;

import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player the backend currently knows about, across every connected Minecraft server -
 * keyed by player UUID, not by connection, which is what lets violation state survive a player
 * switching servers on the same network instead of resetting on every quit/rejoin.
 *
 * <p>A quit does not immediately remove a player (see spec: cross-server switches shouldn't lose
 * VL) - only {@link #evictIdleOlderThan} does, once nobody has sent activity for that player
 * across any connection for longer than the configured idle timeout.
 */
public final class PlayerRegistry {

    private final Map<UUID, PlayerData> byId = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActivityMillis = new ConcurrentHashMap<>();

    public PlayerData getOrCreate(UUID id, String name) {
        PlayerData data = byId.computeIfAbsent(id, k -> new PlayerData(id, name));
        lastActivityMillis.put(id, System.currentTimeMillis());
        return data;
    }

    /**
     * For callers (movement/attack packets) that only have a player's UUID, not their real name -
     * e.g. a packet that arrives before that player's own join message does, such as right after a
     * server restart while they're already connected. Real join handling calls {@link
     * PlayerData#updateName} once the actual name is known, rather than a movement/attack packet
     * permanently baking in a placeholder.
     */
    public PlayerData getOrCreate(UUID id) {
        return getOrCreate(id, "Unknown-" + id.toString().substring(0, 8));
    }

    public PlayerData get(UUID id) {
        return byId.get(id);
    }

    public void touch(UUID id) {
        touch(id, System.currentTimeMillis());
    }

    /** Test seam - production callers use {@link #touch(UUID)}. */
    void touch(UUID id, long nowMillis) {
        lastActivityMillis.put(id, nowMillis);
    }

    public void markQuit(UUID id) {
        touch(id); // still eligible for eviction later; not removed now
    }

    /** Removes and returns the IDs of every player idle longer than {@code idleMillis}. */
    public List<UUID> evictIdleOlderThan(long idleMillis, long nowMillis) {
        List<UUID> evicted = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : lastActivityMillis.entrySet()) {
            if (nowMillis - entry.getValue() > idleMillis) {
                evicted.add(entry.getKey());
            }
        }
        for (UUID id : evicted) {
            byId.remove(id);
            lastActivityMillis.remove(id);
        }
        return evicted;
    }
}
