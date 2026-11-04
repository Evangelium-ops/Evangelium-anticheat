package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Detects "silent aim" - a hit registering while the attacker's actual crosshair/rotation never
 * moved anywhere near the target at all. Both {@link RotationTrendAnalyzer} and {@link
 * AimPrecisionAnalyzer} key off the attacker's rotation correlating with the target in some way
 * (a snap, a precise correction) - silent aim doesn't bother with any of that, since it fakes the
 * hit registration without ever needing to actually point the client's crosshair at the target.
 *
 * <p>This just checks whether the attacker was facing anywhere in the target's general direction
 * at the moment of the attack. A single wildly-off reading could be a backtrack/lag artifact - a
 * sustained pattern of it across several separate attacks is what's actually implausible, since
 * real vanilla hit registration requires roughly aiming at the target in the first place.
 */
public final class SilentAimAnalyzer {

    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    // Generous on purpose: real vanilla hit registration needs the crosshair roughly on the
    // target, but the target's hitbox size, our own backtrack/tick-snap imprecision, and normal
    // aiming looseness (especially vertically, since standing hitboxes are tall) all need slack.
    // An attack landing beyond this isn't achievable through normal aiming at all. Unverified
    // against real playtest data yet - may need retuning the way ReachA's tolerance did.
    private static final double IMPLAUSIBLE_ANGLE_DEGREES = 40.0;
    // A *majority* of recent attacks being nowhere near the target is the actual tell - an
    // occasional wild one could be lag, a whiff, or our own backtrack imprecision.
    private static final double SUSPICIOUS_FRACTION = 0.5;

    /** Per-player rolling state this analyzer needs across many attacks - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Boolean> recentImplausible = new ArrayDeque<>();

        void record(boolean implausible) {
            recentImplausible.addLast(implausible);
            while (recentImplausible.size() > HISTORY_SIZE) {
                recentImplausible.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentImplausible);
        }
    }

    /**
     * @param history    the attacker's recent movement/rotation history, oldest first
     * @param attackTick the tick of the sample closest to the moment of the attack
     * @param target     the target's backtracked position/hitbox at that same tick
     * @param state      this player's rolling analyzer state, stashed via {@code
     *                   PlayerData.computeCheckState}
     * @return a suspicion score from 0.0 (looks legitimate) to 1.0 (never aiming anywhere near target)
     */
    public double analyze(Deque<MovementSample> history, long attackTick, PositionSnapshot target, State state) {
        List<MovementSample> ordered = List.copyOf(history);
        int attackIndex = AimGeometry.indexOfClosest(ordered, attackTick);
        if (attackIndex < 0) {
            return 0.0;
        }
        MovementSample atAttack = ordered.get(attackIndex);
        double error = AimGeometry.angularErrorDegrees(atAttack, target);
        state.record(error > IMPLAUSIBLE_ANGLE_DEGREES);

        List<Boolean> recent = state.recent();
        if (recent.size() < MIN_SAMPLES) {
            return 0.0; // not enough attacks yet to tell a pattern from one bad reading
        }

        long implausibleCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = (double) implausibleCount / recent.size();
        if (fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
