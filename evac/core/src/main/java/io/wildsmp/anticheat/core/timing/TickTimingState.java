package io.wildsmp.anticheat.core.timing;

/**
 * Tracks server tick timing so packet timestamps can be compared against actual elapsed time
 * rather than assumed 20 TPS - keeps a lagging server from producing false flags in later
 * movement/combat checks.
 */
public final class TickTimingState {

    private static final double TARGET_TPS = 20.0;
    private static final long TARGET_TICK_NANOS = 50_000_000L;
    private static final double EMA_ALPHA = 0.1;

    private long lastTickNanos = -1;
    private double emaTickNanos = TARGET_TICK_NANOS;

    public synchronized void onServerTick(long nowNanos) {
        if (lastTickNanos >= 0) {
            long delta = nowNanos - lastTickNanos;
            if (delta > 0) {
                emaTickNanos = EMA_ALPHA * delta + (1 - EMA_ALPHA) * emaTickNanos;
            }
        }
        lastTickNanos = nowNanos;
    }

    public synchronized double currentTps() {
        double raw = 1_000_000_000.0 / Math.max(1.0, emaTickNanos);
        return Math.min(TARGET_TPS, raw);
    }

    /** &gt;= 1.0 when the server is lagging (TPS below target); multiply allowed thresholds by this. */
    public synchronized double lagCompensationFactor() {
        return TARGET_TPS / Math.max(1.0, currentTps());
    }
}
