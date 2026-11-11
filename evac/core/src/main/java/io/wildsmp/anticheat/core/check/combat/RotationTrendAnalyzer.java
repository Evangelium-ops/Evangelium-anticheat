package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Analyzes a player's rotation history around an attack for patterns real human aim doesn't
 * produce: an instant snap to the target immediately reverted, an angular-velocity outlier
 * against that same player's own recent behavior, or suspiciously identical snap magnitude
 * across many separate attacks. Grounded in the player's own recent history rather than a global
 * threshold, since legitimate flick-aim speed varies a lot between players.
 */
public final class RotationTrendAnalyzer {

    private static final int LOOKBACK_SAMPLES = 5;
    private static final int LOOKAHEAD_SAMPLES = 3;
    private static final int HISTORY_SIZE_FOR_CONSISTENCY = 10;
    private static final double MIN_SNAP_DEGREES = 5.0;

    /** Per-player rolling state this analyzer needs across many attacks - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Double> recentAttackDeltas = new ArrayDeque<>();

        void record(double delta) {
            recentAttackDeltas.addLast(delta);
            while (recentAttackDeltas.size() > HISTORY_SIZE_FOR_CONSISTENCY) {
                recentAttackDeltas.removeFirst();
            }
        }

        List<Double> recent() {
            return List.copyOf(recentAttackDeltas);
        }
    }

    /**
     * @param history           the attacker's recent movement/rotation history, oldest first
     * @param attackTickTime    the tick of the sample closest to the moment of the attack
     * @param nextAttackTickTime the tick of the next already-queued attack, or {@code
     *                          Long.MAX_VALUE} if none - bounds the lookahead window so a combo's
     *                          next attack doesn't contaminate this attack's revert evaluation
     * @param state             this player's rolling analyzer state, stashed via {@code
     *                          PlayerData.computeCheckState}
     * @return a suspicion score, roughly 0.0 (looks legitimate) to 3.0 (highly suspicious across
     *         all three signals) - not capped, so a caller can scale it into a VL increment
     */
    /** Convenience overload for callers with no other attack queued to bound the lookahead against. */
    public double analyze(Deque<MovementSample> history, long attackTickTime, State state) {
        return analyze(history, attackTickTime, Long.MAX_VALUE, state);
    }

    public double analyze(Deque<MovementSample> history, long attackTickTime, long nextAttackTickTime, State state) {
        List<MovementSample> ordered = List.copyOf(history);
        int attackIndex = indexOfClosest(ordered, attackTickTime);
        if (attackIndex <= 0) {
            return 0.0; // no usable history yet
        }

        double baseline = averageDeltaBefore(ordered, attackIndex);
        double[] intoVector = deltaVectorAt(ordered, attackIndex);
        double[] afterVector = deltaVectorAfter(ordered, attackIndex, nextAttackTickTime);
        double deltaIntoAttack = magnitude(intoVector);

        double rawSpikeScore = spikeScore(baseline, deltaIntoAttack);
        double revertScore = revertScore(intoVector, afterVector);

        state.record(deltaIntoAttack);
        double rawConsistencyScore = consistencyScore(state.recent());

        // A big rotation relative to recent baseline is, on its own, exactly what a legitimate
        // flick onto a freshly (re)acquired target looks like - every human combo does this. Only
        // pair it with an actual revert (snapped on, then immediately snapped back) - not with mere
        // magnitude consistency, which a skilled human repeating the same technique (jump-critting
        // the same target, say - jump height and engagement distance are both fairly fixed) can
        // legitimately reproduce with very little variance. Confirmed live: coupling it to
        // consistency alone flagged every legitimate jump-crit hit.
        double spikeScore = revertScore > 0.0 ? rawSpikeScore : 0.0;

        // Demoted to a corroborating nudge rather than a standalone trigger, for the same reason -
        // identical magnitude alone is too easily produced by legitimate repeated technique to
        // single-handedly cross the flag threshold.
        double consistencyScore = Math.min(rawConsistencyScore, 0.2);

        return spikeScore + revertScore + consistencyScore;
    }

    private static int indexOfClosest(List<MovementSample> ordered, long attackTickTime) {
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < ordered.size(); i++) {
            long diff = Math.abs(ordered.get(i).tickTimestamp() - attackTickTime);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    private static double angleDelta(MovementSample a, MovementSample b) {
        double dYaw = Math.abs(normalizeDegrees(b.yaw() - a.yaw()));
        double dPitch = Math.abs(b.pitch() - a.pitch());
        return dYaw + dPitch;
    }

    /** Signed {yaw, pitch} rotation from {@code a} to {@code b}, preserving direction. */
    private static double[] angleDeltaVector(MovementSample a, MovementSample b) {
        double dYaw = normalizeDegrees(b.yaw() - a.yaw());
        double dPitch = b.pitch() - a.pitch();
        return new double[] {dYaw, dPitch};
    }

    private static double magnitude(double[] vector) {
        return Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1]);
    }

    private static double normalizeDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static double[] deltaVectorAt(List<MovementSample> ordered, int attackIndex) {
        return angleDeltaVector(ordered.get(attackIndex - 1), ordered.get(attackIndex));
    }

    private static double[] deltaVectorAfter(List<MovementSample> ordered, int attackIndex, long nextAttackTickTime) {
        int end = Math.min(ordered.size() - 1, attackIndex + LOOKAHEAD_SAMPLES);
        // Don't let the window reach into a sample at or after the next queued attack - that
        // motion belongs to the next attack's own snap-in, not this attack's aftermath.
        while (end > attackIndex && ordered.get(end).tickTimestamp() >= nextAttackTickTime) {
            end--;
        }
        if (end <= attackIndex) {
            return new double[] {0.0, 0.0};
        }
        return angleDeltaVector(ordered.get(attackIndex), ordered.get(end));
    }

    private static double averageDeltaBefore(List<MovementSample> ordered, int attackIndex) {
        int from = Math.max(1, attackIndex - LOOKBACK_SAMPLES);
        int to = attackIndex - 1;
        if (to < from) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (int i = from; i <= to; i++) {
            sum += angleDelta(ordered.get(i - 1), ordered.get(i));
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double spikeScore(double baseline, double deltaIntoAttack) {
        double threshold = Math.max(MIN_SNAP_DEGREES, baseline * 3.0);
        if (deltaIntoAttack <= threshold) {
            return 0.0;
        }
        return Math.min(1.0, (deltaIntoAttack - threshold) / threshold);
    }

    /**
     * Flags a snap that gets immediately undone - not just "rotated a lot again afterward", which
     * is exactly what legitimate tracking of a moving target looks like (aim keeps moving roughly
     * the same direction as the target does). Only a rotation that reverses direction relative to
     * the snap-into-attack vector is suspicious, so this compares the two vectors' directions via
     * cosine similarity rather than the after-window's raw magnitude.
     */
    private static double revertScore(double[] intoVector, double[] afterVector) {
        double intoMag = magnitude(intoVector);
        if (intoMag < MIN_SNAP_DEGREES) {
            return 0.0; // no meaningful snap to begin with
        }
        double afterMag = magnitude(afterVector);
        if (afterMag < MIN_SNAP_DEGREES * 0.5) {
            return 0.0; // barely moved after - can't tell a revert from just holding aim
        }
        double dot = intoVector[0] * afterVector[0] + intoVector[1] * afterVector[1];
        double cosine = dot / (intoMag * afterMag);
        if (cosine > -0.3) {
            return 0.0; // same general direction (or perpendicular) - continued tracking, not a revert
        }
        return Math.min(1.0, -cosine);
    }

    // A repeated, deliberate technique (e.g. jump-critting the same target - jump height and
    // typical engagement distance are both fairly fixed) legitimately produces a similar-magnitude
    // aim correction hit after hit; that's consistent PRECISION, not robotic uniformity. 0.15 was
    // tuned tighter than real human motor variance for a repeated action and flagged every single
    // jump-crit hit live. A bot's tell is precision far beyond what a hand can reproduce, not mere
    // similarity - 0.30 gives room for legitimate repetition while still catching that.
    private static final double CONSISTENCY_COEFFICIENT_OF_VARIATION_THRESHOLD = 0.30;

    private static double consistencyScore(List<Double> recentDeltas) {
        if (recentDeltas.size() < 4) {
            return 0.0; // not enough history yet
        }
        double mean = recentDeltas.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean < MIN_SNAP_DEGREES) {
            return 0.0; // these aren't real snaps to begin with
        }
        double variance = recentDeltas.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        double coefficientOfVariation = stdDev / mean;
        if (coefficientOfVariation > CONSISTENCY_COEFFICIENT_OF_VARIATION_THRESHOLD) {
            return 0.0; // enough natural variance to look human
        }
        return Math.min(1.0, (CONSISTENCY_COEFFICIENT_OF_VARIATION_THRESHOLD - coefficientOfVariation)
                / CONSISTENCY_COEFFICIENT_OF_VARIATION_THRESHOLD);
    }
}
