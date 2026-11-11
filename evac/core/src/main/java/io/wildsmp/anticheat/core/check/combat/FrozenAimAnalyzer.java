package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a camera that never moves *despite the target requiring it to* - the attacker's own
 * yaw/pitch showing essentially zero variance while the target's position changed enough that
 * tracking it would need real aim adjustment. Confirmed live: an aim-assist that landed repeated
 * hits on a visibly moving target while the attacker's reported yaw/pitch stayed bit-for-bit
 * identical across 9+ real seconds - {@link AimPrecisionAnalyzer} never caught this, since it
 * measures error *relative to the target*, which still varies naturally as the target moves even
 * with a frozen camera.
 *
 * <p>Frozen camera alone is NOT the tell - real mice don't emit spurious movement when genuinely
 * at rest, so a player legitimately holding still while repeatedly hitting a target that also
 * isn't moving relative to them will show exactly this "zero jitter" pattern, and that's
 * completely ordinary. It only becomes suspicious paired with the target having genuinely moved
 * out of (or into) the frozen aim's real coverage during the same window - see {@link
 * AimGeometry#requiredTrackingAcrossWindow} for why this is a ray-vs-hitbox coverage flip rather
 * than a raw distance or angle-to-center-point measurement: ordinary melee knockback and jump-crit
 * bobbing - on either player's side - swing the target's exact center point around by a lot without
 * ever actually taking it out of a frozen aim's real, hitbox-sized coverage.
 */
public final class FrozenAimAnalyzer {

    private static final Logger LOG = Logger.getLogger(FrozenAimAnalyzer.class.getName());
    public static final int WINDOW_TICKS = 20; // 1 second at 20 ticks/sec
    private static final int MIN_SAMPLES_IN_WINDOW = 10; // at least half a second of real coverage
    // Real mouse/controller input essentially never holds this still for a full second - genuine
    // human "not moving" still drifts by more than this from sensor noise alone. Unverified against
    // real playtest data yet - may need retuning the way ReachA's tolerance did.
    private static final double FROZEN_DEGREES = 0.05;
    // The continuous caller records one reading per TICK that needed tracking, not per attack, so
    // these are tick counts: nearly a full 30 ticks' worth of needed-tracking readings must be
    // frozen before this flags. The original 8-reading/50% setting meant a player simply not
    // touching their mouse for well under a second of a fight read as an aim assist, which flagged
    // real players constantly once many people were fighting (confirmed live on a Leaf server). A
    // real assist holds the camera bit-for-bit still for many seconds.
    static final int DEFAULT_HISTORY_SIZE = 30;
    static final int DEFAULT_MIN_SAMPLES = 30;
    static final double DEFAULT_SUSPICIOUS_FRACTION = 0.9;

    private final int historySize;
    private final int minSamples;
    private final double suspiciousFraction;

    public FrozenAimAnalyzer() {
        this(DEFAULT_HISTORY_SIZE, DEFAULT_MIN_SAMPLES, DEFAULT_SUSPICIOUS_FRACTION);
    }

    /** Lets tests (and future tuning) use different evidence thresholds without touching the defaults. */
    public FrozenAimAnalyzer(int historySize, int minSamples, double suspiciousFraction) {
        this.historySize = historySize;
        this.minSamples = minSamples;
        this.suspiciousFraction = suspiciousFraction;
    }

    /** Per-player rolling state this analyzer needs across many attacks - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Boolean> recentFrozen = new ArrayDeque<>();

        void record(boolean frozen, int historySize) {
            recentFrozen.addLast(frozen);
            while (recentFrozen.size() > historySize) {
                recentFrozen.removeFirst();
            }
        }

        /** Forgets the streak - called when the player disengages from combat so it can't carry over. */
        public void reset() {
            recentFrozen.clear();
        }

        List<Boolean> recent() {
            return List.copyOf(recentFrozen);
        }
    }

    /**
     * @param history             the attacker's recent movement/rotation history, oldest first
     * @param attackTick          the tick of the attack being evaluated
     * @param trackingWasRequired true if the target genuinely moved out of (or into) the frozen
     *                            aim's real hitbox coverage during this window - false if it's
     *                            stationary, unknown, there's no current target, or it merely
     *                            wobbled vertically (knockback, jump-critting) without actually
     *                            leaving where the frozen aim already covered
     * @param state               this player's rolling analyzer state, stashed via {@code
     *                            PlayerData.computeCheckState}
     * @return a suspicion score from 0.0 (real jitter present, or nothing required tracking) to
     *         1.0 (sustained frozen camera despite a target that needed tracking)
     */
    public double analyze(Deque<MovementSample> history, long attackTick, boolean trackingWasRequired, State state) {
        List<MovementSample> ordered = List.copyOf(history);
        List<MovementSample> window = ordered.stream()
                .filter(s -> attackTick - s.tickTimestamp() >= 0 && attackTick - s.tickTimestamp() <= WINDOW_TICKS)
                .toList();
        if (window.size() < MIN_SAMPLES_IN_WINDOW) {
            return 0.0; // not enough recent coverage to judge either way
        }
        if (!trackingWasRequired) {
            return 0.0; // target never actually left the frozen aim's real coverage - holding still is ordinary
        }

        double yawRange = range(window, MovementSample::yaw);
        double pitchRange = range(window, MovementSample::pitch);
        boolean frozen = yawRange <= FROZEN_DEGREES && pitchRange <= FROZEN_DEGREES;
        state.record(frozen, historySize);

        List<Boolean> recent = state.recent();
        long frozenCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) frozenCount / recent.size();
        LOG.log(Level.INFO, "FrozenAim: window {0} samples, yaw range {1}, pitch range {2}, frozen {3}, "
                        + "tracking required {4}, recent fraction {5}",
                new Object[] {window.size(), yawRange, pitchRange, frozen, trackingWasRequired, fraction});

        if (recent.size() < minSamples) {
            return 0.0; // not enough readings yet to tell a sustained pattern from one moment
        }
        if (fraction < suspiciousFraction) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - suspiciousFraction) / (1.0 - suspiciousFraction));
    }

    private static double range(List<MovementSample> window, ToDoubleFunction<MovementSample> extractor) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (MovementSample sample : window) {
            double value = extractor.applyAsDouble(sample);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }
}
