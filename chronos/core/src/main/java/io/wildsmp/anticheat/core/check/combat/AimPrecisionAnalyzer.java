package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects aim-assist style aiming, which {@link RotationTrendAnalyzer} is structurally the wrong
 * shape to catch: a bot that snaps onto a target and then reverts back has a discrete "before /
 * during / after" signature to compare, but assisted aim doesn't need to snap or revert anywhere
 * at all - a smooth, continuous auto-tracker never has a moment where it was meaningfully
 * off-target to begin with, since it's kept the crosshair glued to the target the whole time.
 * Confirmed live: gating this on "was there a real correction first" meant a smooth-tracking
 * assist never produced a single data point, ever - the gate itself was the bug.
 *
 * <p>This measures the angular error between where the attacker was actually facing and the true
 * direction to the target's hitbox at the moment of every attack, unconditionally. A single close
 * hit isn't damning on its own - that can be luck or a genuinely skilled player. What's actually
 * implausible by hand is the *average* staying this tight across several separate attacks; real
 * human aim, even very good aim, has natural moment-to-moment jitter that shows up as variance
 * over multiple hits.
 */
public final class AimPrecisionAnalyzer {

    private static final Logger LOG = Logger.getLogger(AimPrecisionAnalyzer.class.getName());
    private static final int HISTORY_SIZE = 8;
    // Higher than a "was there a real correction" design would need, since without that filter
    // this also has to distinguish a genuinely skilled human from an assist on averages alone -
    // more samples before concluding anything reduces the risk of a lucky streak reading as proof.
    private static final int MIN_SAMPLES = 5;
    // Real human aim - even very good aim - has natural moment-to-moment jitter; a sustained
    // average this tight across several separate attacks is not something a hand reproduces.
    // Unverified against real playtest data yet - may need retuning the way ReachA's tolerance did.
    private static final double SUSPICIOUS_AVERAGE_DEGREES = 4.0;

    /** Per-player rolling state this analyzer needs across many attacks - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Double> recentErrors = new ArrayDeque<>();

        void record(double errorDegrees) {
            recentErrors.addLast(errorDegrees);
            while (recentErrors.size() > HISTORY_SIZE) {
                recentErrors.removeFirst();
            }
        }

        List<Double> recent() {
            return List.copyOf(recentErrors);
        }
    }

    /**
     * @param history    the attacker's recent movement/rotation history, oldest first
     * @param attackTick the tick of the sample closest to the moment of the attack
     * @param target     the target's backtracked position/hitbox at that same tick
     * @param state      this player's rolling analyzer state, stashed via {@code
     *                   PlayerData.computeCheckState}
     * @return a suspicion score from 0.0 (looks legitimate) to 1.0 (maximally precise average)
     */
    public double analyze(Deque<MovementSample> history, long attackTick, PositionSnapshot target, State state) {
        List<MovementSample> ordered = List.copyOf(history);
        int attackIndex = AimGeometry.indexOfClosest(ordered, attackTick);
        if (attackIndex < 0) {
            LOG.log(Level.INFO, "AimPrecision: empty attacker history at tick {0} - can''t evaluate this attack", attackTick);
            return 0.0;
        }
        MovementSample atAttack = ordered.get(attackIndex);
        double atAttackError = AimGeometry.angularErrorDegrees(atAttack, target);
        state.record(atAttackError);

        List<Double> recent = state.recent();
        double average = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        LOG.log(Level.INFO, "AimPrecision: attacker at ({0},{1},{2}) yaw {3} pitch {4}, target at ({5},{6},{7}) height {8}, "
                        + "error {9} deg, samples {10}, average {11} deg",
                new Object[] {atAttack.x(), atAttack.y(), atAttack.z(), atAttack.yaw(), atAttack.pitch(),
                        target.x(), target.y(), target.z(), target.height(), atAttackError, recent.size(), average});

        if (recent.size() < MIN_SAMPLES) {
            return 0.0; // not enough attacks yet to tell sustained precision from a lucky streak
        }
        if (average >= SUSPICIOUS_AVERAGE_DEGREES) {
            return 0.0;
        }
        return Math.min(1.0, (SUSPICIOUS_AVERAGE_DEGREES - average) / SUSPICIOUS_AVERAGE_DEGREES);
    }
}
