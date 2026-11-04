package io.wildsmp.anticheat.core.check.combat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A ring buffer keyed by server tick number (not chronological push/pop like {@link
 * io.wildsmp.anticheat.core.player.PlayerData#movementHistory}), covering roughly the last 1-2
 * seconds at 20 ticks/sec. {@link ReachCheck} queries a target's history at a specific past tick
 * to backtrack against.
 */
public final class TickIndexedPositionHistory {

    public static final int WINDOW_TICKS = 40;
    // No legitimate single-tick movement (sprint, jump, elytra, knockback) covers this much ground
    // - only a teleport (/tp, a warp, a portal, respawn) does. Without this, a target's history
    // window can hold both a pre-teleport and a post-teleport position at once, and a nearby-tick
    // backtrack lookup has no way to know they aren't spatially continuous - confirmed live, a
    // target teleported ~115 blocks and back left a stale snapshot that got matched against a
    // completely unrelated later attack, computing a bogus multi-hundred-block "reach" flag.
    private static final double TELEPORT_JUMP_THRESHOLD_BLOCKS = 10.0;

    private final Map<Long, PositionSnapshot> byTick = new ConcurrentHashMap<>();
    private volatile PositionSnapshot mostRecent;

    public void record(PositionSnapshot snapshot) {
        PositionSnapshot previous = mostRecent;
        if (previous != null && snapshot.tick() > previous.tick()
                && previous.centerDistanceTo(snapshot) > TELEPORT_JUMP_THRESHOLD_BLOCKS) {
            // Everything on record predates this jump and is no longer safe to backtrack against.
            byTick.clear();
        }
        if (previous == null || snapshot.tick() >= previous.tick()) {
            mostRecent = snapshot;
        }
        byTick.put(snapshot.tick(), snapshot);
        evictOlderThan(snapshot.tick() - WINDOW_TICKS);
    }

    private void evictOlderThan(long cutoffExclusive) {
        byTick.keySet().removeIf(tick -> tick <= cutoffExclusive);
    }

    // A target's client doesn't send a movement packet on literally every tick under all
    // conditions (e.g. no rotation/position delta to report), so an exact-only lookup can miss a
    // real snapshot that exists just a tick or two away - silently finding nothing and never
    // flagging at all, rather than backtracking against the closest position actually on record.
    private static final int NEAREST_TICK_TOLERANCE = 3;

    public Optional<PositionSnapshot> at(long tick) {
        PositionSnapshot exact = byTick.get(tick);
        if (exact != null) {
            return Optional.of(exact);
        }
        PositionSnapshot closest = null;
        long closestDistance = Long.MAX_VALUE;
        for (int offset = 1; offset <= NEAREST_TICK_TOLERANCE; offset++) {
            PositionSnapshot before = byTick.get(tick - offset);
            if (before != null && offset < closestDistance) {
                closest = before;
                closestDistance = offset;
            }
            PositionSnapshot after = byTick.get(tick + offset);
            if (after != null && offset < closestDistance) {
                closest = after;
                closestDistance = offset;
            }
        }
        return Optional.ofNullable(closest);
    }
}
