package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a client running its own tick loop faster than real time - a "timer" hack sends movement
 * packets faster than the legitimate one per 50ms. Measured purely in REAL time: how many packets
 * arrived within the trailing {@link #WINDOW_NANOS}, against how many a normal client sends over
 * that same span (one per {@link #NANOS_PER_TICK}). Deliberately does NOT use the server's own tick
 * counter: when the server lags, its tick clock stalls and then catches up in a burst, which made
 * consecutive packets 50ms apart look like two ticks passing in one (confirmed live on a Leaf
 * server - 98% of false "too fast" samples were exactly that, and the old per-pair check also
 * silently dropped the matching stalled pairs, so the distortion never averaged out). A long
 * window absorbs network jitter bunching for the same reason. Exempts {@link
 * MovementEffects#recentlyLaunched()} - a server-driven velocity impulse (wind charge, explosion,
 * plugin ability) can make a client send a burst of packets as it resolves the launch.
 */
public final class TimerAnalyzer {

    private static final Logger LOG = Logger.getLogger(TimerAnalyzer.class.getName());
    private static final long NANOS_PER_TICK = 50_000_000L;
    // A long window on purpose: after a client freeze or a network stall the queued packets arrive in one burst, and in a short
    // window that burst alone read as a client running faster than real time (a normal player at a laggy moment flagged TimerA).
    // Over 10 seconds a backlog of a few seconds' worth of packets is averaged out, while a real timer hack still shows its
    // sustained excess within seconds.
    private static final long WINDOW_NANOS = 10_000_000_000L;
    // Won't judge until at least this much real time of packets is in the window - a shorter span
    // is dominated by jitter, not by the client's real tick rate.
    private static final long MIN_SPAN_NANOS = 5_000_000_000L;
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    private static final double SUSPICIOUS_FRACTION = 0.5;
    // A real client's tick loop and network jitter both add legitimate variance to arrival timing
    // - this only flags a packet rate unambiguously faster than real time, not merely "a bit
    // fast." Unverified against real playtest data yet - may need retuning.
    private static final double MAX_LEGITIMATE_SPEEDUP_RATIO = 1.4;

    public static final class State {
        private final Deque<Long> arrivals = new ArrayDeque<>();
        private final Deque<Boolean> recentTooFast = new ArrayDeque<>();

        void record(boolean tooFast) {
            recentTooFast.addLast(tooFast);
            while (recentTooFast.size() > HISTORY_SIZE) {
                recentTooFast.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentTooFast);
        }
    }

    public double analyze(MovementSample previous, MovementSample current, State state) {
        if (current.effects().recentlyLaunched()) {
            return 0.0; // not evidence either way - see class Javadoc
        }
        long now = current.nanoTime();
        Long newest = state.arrivals.peekLast();
        if (newest != null && now <= newest) {
            return 0.0; // duplicate/out-of-order sample - no real interval to judge
        }
        state.arrivals.addLast(now);
        while (state.arrivals.peekFirst() != null && now - state.arrivals.peekFirst() > WINDOW_NANOS) {
            state.arrivals.removeFirst();
        }

        long span = now - state.arrivals.peekFirst();
        if (span < MIN_SPAN_NANOS) {
            return 0.0; // not enough real time observed yet to say anything
        }
        double packetsPerNormalClient = (double) span / NANOS_PER_TICK;
        double ratio = (state.arrivals.size() - 1) / packetsPerNormalClient;
        boolean tooFast = ratio > MAX_LEGITIMATE_SPEEDUP_RATIO;
        state.record(tooFast);

        List<Boolean> recent = state.recent();
        long tooFastCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) tooFastCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "Timer: packets {0} over {1}ns, rate ratio {2}, tooFast {3}, samples {4}, recent fraction {5}",
                new Object[] {state.arrivals.size() - 1, span, ratio, tooFast, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
