package ac.grim.grimac.manager;

import java.util.HashMap;
import java.util.Map;

/**
 * Evangelium: tells a burst of flags from a stray one. A key (a check) is "in a burst" once it has been noted
 * {@code flags} times within {@code windowMs}. Used to hold staff alerts back until a check keeps flagging.
 */
final class FlagBurst<K> {
    private final int flags;
    private final long windowMs;
    // per key: the last `flags` note times (oldest first), then how many notes the current burst has had
    private final Map<K, long[]> recent = new HashMap<>();

    FlagBurst(int flags, long windowMs) {
        this.flags = flags;
        this.windowMs = windowMs;
    }

    /** Records one flag of this key at time {@code now} (ms). */
    synchronized void note(K key, long now) {
        long[] times = recent.computeIfAbsent(key, k -> new long[flags + 1]);
        System.arraycopy(times, 1, times, 0, flags - 1);
        times[flags - 1] = Math.max(1, now);   // 0 means "no flag yet"
    }

    /**
     * Call once per flag, after {@link #note}: 0 when the key has not flagged {@code flags} times within the window,
     * else how many flags the burst has had since it began (1 = the flag that completed it).
     */
    synchronized int count(K key, long now) {
        long[] times = recent.get(key);
        if (times == null) return 0;
        boolean in = times[0] != 0 && now - times[0] <= windowMs;
        times[flags] = in ? times[flags] + 1 : 0;
        return (int) Math.min(Integer.MAX_VALUE, times[flags]);
    }
}
