package io.wildsmp.anticheat.core.check.combat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estimates how many ticks behind real-time a client's view currently is, by sending a
 * transaction/ping-style packet on a steady interval and tracking round-trip acknowledgment. This
 * is what makes {@link ReachCheck}'s backtrack accurate under real, jittery ping rather than a
 * flat {@code ping / 2} guess. Smoothed via exponential moving average, matching {@link
 * io.wildsmp.anticheat.core.timing.TickTimingState}'s existing pattern, so a single noisy sample
 * doesn't cause a wild swing.
 */
public final class TickSyncTracker {

    private static final double EMA_ALPHA = 0.2;

    private final Map<Integer, Long> pendingTransactions = new ConcurrentHashMap<>();
    private final AtomicInteger nextTransactionId = new AtomicInteger(0);
    private volatile double emaTickLag = 0.0;
    private volatile boolean hasSample = false;

    public int sendTransaction(long currentTick) {
        int id = nextTransactionId.incrementAndGet();
        pendingTransactions.put(id, currentTick);
        return id;
    }

    public void onTransactionAcknowledged(int transactionId, long currentTick) {
        Long sentAtTick = pendingTransactions.remove(transactionId);
        if (sentAtTick == null) {
            return;
        }
        double sample = Math.max(0, currentTick - sentAtTick);
        if (!hasSample) {
            emaTickLag = sample;
            hasSample = true;
        } else {
            emaTickLag = EMA_ALPHA * sample + (1 - EMA_ALPHA) * emaTickLag;
        }
    }

    public long estimatedTickLag() {
        return Math.round(emaTickLag);
    }

    /** The server tick this client's view currently corresponds to, given the actual current tick. */
    public long clientPerceivedTick(long currentServerTick) {
        return Math.max(0, currentServerTick - estimatedTickLag());
    }
}
