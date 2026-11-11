package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a trigger bot - a module that fires the attack the instant the crosshair lines up with
 * a target, removing the human reaction-time delay between "aim arrives on target" and "click
 * registers." Structurally different from every other combat signal in this package: those all
 * look at whether the AIM is correct (a snap, a precise correction, a ray that touches the real
 * hitbox); a trigger bot doesn't touch aim at all - the player aims manually - it only automates
 * the CLICK's timing. So this looks at timing, not accuracy.
 *
 * <p>For each attack, this finds how many consecutive ticks immediately before it the aim was
 * already actually touching the target's hitbox - i.e. how long the crosshair had been on the
 * target before the click fired. Real human reaction time (see it land, then click) is at least a
 * few ticks even for a fast player, and varies attack to attack; a trigger bot's delay is close to
 * zero and, more tellingly, stays close to zero and consistent across many separate attacks.
 *
 * <p>Tried and reverted: a second signal based on the tick interval between consecutive on-target
 * attacks, meant to catch the "hold the crosshair on the target and let it auto-fire" usage
 * pattern that never produces an arrival event for the signal above to measure. Confirmed live
 * that this doesn't work on this server: an ordinary player spam-clicking faster than a weapon's
 * attack-cooldown gets rate-limited by that cooldown to a perfectly fixed interval regardless of
 * their actual, irregular click timing - so every fast human clicker reads as "robotically
 * consistent," not just a real trigger bot. There's currently no reliable way to tell the two
 * apart from attack-packet timing alone on a cooldown-gated server, so this only covers the
 * "swing the crosshair onto a new target and it fires instantly" usage pattern.
 *
 * <p>"On target" is a real ray-vs-hitbox test (see {@link AimGeometry#rayIntersectsBox}, same as
 * {@link HitboxAnalyzer} uses), not an angular cone around the target's center point. A cone
 * anchored to the center is misled by the attacker's own vertical motion - jump-critting bobs the
 * eye up and down by over a block every cycle, which swings the pitch needed to point at a fixed
 * center point by tens of degrees even though the frozen crosshair never actually left the
 * target's (1.8-block-tall) hitbox at any point. Confirmed live: that false-flagged completely
 * ordinary jump-crit spam as trigger-botting.
 */
public final class TriggerBotAnalyzer {

    private static final Logger LOG = Logger.getLogger(TriggerBotAnalyzer.class.getName());

    // Bounds the raycast the same distance ReachCheck already allows, reusing its established,
    // live-tuned tolerance rather than picking a second, possibly-inconsistent one.
    private static final double MAX_RAY_DISTANCE_BLOCKS = ReachCheck.VANILLA_MAX_REACH + ReachCheck.EPSILON;
    // Same tick-snap/jump tolerance HitboxAnalyzer pads its raycast with.
    private static final double RAY_PADDING_BLOCKS = ReachCheck.EPSILON;
    // 100ms - even a very fast, primed human reacting to a crosshair landing on target rarely
    // clicks this fast, and real reaction time varies attack to attack far more than a script's
    // does. Unverified against real playtest data yet - may need retuning too.
    private static final long SUSPICIOUS_REACTION_TICKS = 2;
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    // A majority of recent attacks firing this fast is the actual tell - one could be a lucky
    // click timed right as the crosshair happened to arrive.
    private static final double SUSPICIOUS_FRACTION = 0.5;

    /** Per-player rolling state this analyzer needs across many attacks - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Boolean> recentInstant = new ArrayDeque<>();

        void record(boolean instant) {
            recentInstant.addLast(instant);
            while (recentInstant.size() > HISTORY_SIZE) {
                recentInstant.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentInstant);
        }
    }

    /**
     * @param history    the attacker's recent movement/rotation history, oldest first
     * @param attackTick the tick of the attack being evaluated
     * @param target     the target's backtracked position/hitbox at that same tick
     * @param state      this player's rolling analyzer state, stashed via {@code
     *                   PlayerData.computeCheckState}
     * @return a suspicion score from 0.0 (normal human reaction time) to 1.0 (sustained instant clicks)
     */
    public double analyze(Deque<MovementSample> history, long attackTick, PositionSnapshot target, State state) {
        List<MovementSample> ordered = List.copyOf(history);
        int attackIndex = AimGeometry.indexOfClosest(ordered, attackTick);
        if (attackIndex < 0) {
            LOG.log(Level.INFO, "TriggerBot: no movement sample near attack tick {0} - skipped", attackTick);
            return 0.0;
        }

        MovementSample atAttack = ordered.get(attackIndex);
        double distance = distanceTo(atAttack, target);
        if (!onTarget(atAttack, target)) {
            LOG.log(Level.INFO, "TriggerBot: not on-target at the attack itself (distance {0} blocks) - not a trigger-bot case",
                    distance);
            return 0.0; // wasn't even on-target at the attack itself - not a trigger-bot case at all
        }

        // Walk backward to find where the current on-target streak began.
        int arrival = attackIndex;
        while (arrival > 0 && onTarget(ordered.get(arrival - 1), target)) {
            arrival--;
        }
        if (arrival == 0) {
            // Aim was on-target for the entire available history - can't tell when it actually
            // arrived (may have been well before this window even started), so this attack isn't
            // usable evidence either way.
            LOG.log(Level.INFO, "TriggerBot: on-target for the entire {0}-sample history window - arrival unknown, skipped",
                    ordered.size());
            return 0.0;
        }
        long reactionTicks = ordered.get(attackIndex).tickTimestamp() - ordered.get(arrival).tickTimestamp();

        boolean instant = reactionTicks <= SUSPICIOUS_REACTION_TICKS;
        state.record(instant);

        List<Boolean> recent = state.recent();
        long instantCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) instantCount / recent.size();
        LOG.log(Level.INFO, "TriggerBot: reaction {0} ticks, instant {1}, samples {2}, recent fraction {3}",
                new Object[] {reactionTicks, instant, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES) {
            return 0.0; // not enough attacks yet to tell a pattern from one lucky-timed click
        }
        if (fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }

    private static double distanceTo(MovementSample sample, PositionSnapshot target) {
        double dx = target.x() - sample.x();
        double dy = target.y() + target.height() * 0.5 - (sample.y() + AimGeometry.EYE_HEIGHT);
        double dz = target.z() - sample.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean onTarget(MovementSample sample, PositionSnapshot target) {
        double eyeY = sample.y() + AimGeometry.EYE_HEIGHT;
        double[] direction = AimGeometry.facingVector(sample.yaw(), sample.pitch());
        return AimGeometry.rayIntersectsBox(
                sample.x(), eyeY, sample.z(), direction, target, MAX_RAY_DISTANCE_BLOCKS, RAY_PADDING_BLOCKS);
    }
}
