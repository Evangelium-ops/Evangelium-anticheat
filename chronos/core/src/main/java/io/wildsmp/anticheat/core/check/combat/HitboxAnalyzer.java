package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a "hitbox" cheat - a client-side module that enlarges (or otherwise distorts) the
 * target's interaction bounding box, letting an attack register even though the attacker's
 * actual aim ray never touches the target's real, vanilla-sized hitbox at all.
 *
 * <p>{@link SilentAimAnalyzer} already catches aim that's wildly off-target, but it works off a
 * generous angular threshold - a rough approximation that only flags a large miss. This instead
 * does the real geometry a vanilla client's own raycast requires: a proper ray-vs-box
 * intersection test against the target's true bounding box (see {@link
 * AimGeometry#rayIntersectsBox}), catching the much narrower gap a mildly-enlarged hitbox module
 * actually exploits - aim that's close, but not actually touching the real box.
 */
public final class HitboxAnalyzer {

    private static final Logger LOG = Logger.getLogger(HitboxAnalyzer.class.getName());

    // Bounds the raycast the same distance ReachCheck already allows, reusing its established,
    // live-tuned tolerance rather than picking a second, possibly-inconsistent one.
    private static final double MAX_RAY_DISTANCE_BLOCKS = ReachCheck.VANILLA_MAX_REACH + ReachCheck.EPSILON;
    // Our attacker-position sampling is only as precise as the nearest recorded tick, and during
    // fast vertical motion (a jump-crit's rise and fall) that tick-snap error swings the ray's
    // real origin more than it does in normal ground combat. Same underlying imprecision
    // ReachCheck's own EPSILON compensates for - reusing that value here too. Confirmed live: a
    // genuine jump-crit hit got flagged without this.
    private static final double RAY_PADDING_BLOCKS = ReachCheck.EPSILON;
    // Confirmed live on a Leaf server: 18% of ordinary players' attacks fail a strict single-sample
    // ray test (the aim sample is the last packet BEFORE the click, and both players are moving), so
    // a bare majority of 8 is far too easy to hit by chance for someone who flicks a lot. Judge the
    // aim and target positions around the attack (see analyzeWithTolerance), and demand a sustained
    // streak of misses before flagging.
    private static final int HISTORY_SIZE = 10;
    private static final int MIN_SAMPLES = 6;
    private static final double SUSPICIOUS_FRACTION = 0.7;
    // The click lands somewhere between the last aim packet before the attack and the first one
    // after it - these are how many samples either side get tried.
    static final int TOLERANCE_SAMPLES_BEFORE = 1;
    static final int TOLERANCE_SAMPLES_AFTER = 2;

    /** Per-player rolling state this analyzer needs across many attacks - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Boolean> recentMisses = new ArrayDeque<>();

        void record(boolean missedTrueHitbox) {
            recentMisses.addLast(missedTrueHitbox);
            while (recentMisses.size() > HISTORY_SIZE) {
                recentMisses.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentMisses);
        }
    }

    /**
     * @param history    the attacker's recent movement/rotation history, oldest first
     * @param attackTick the tick of the sample closest to the moment of the attack
     * @param target     the target's backtracked position/hitbox at that same tick
     * @param state      this player's rolling analyzer state, stashed via {@code
     *                   PlayerData.computeCheckState}
     * @return a suspicion score from 0.0 (real hits on the true hitbox) to 1.0 (sustained hits
     *         that shouldn't have registered against the real geometry)
     */
    public double analyze(Deque<MovementSample> history, long attackTick, PositionSnapshot target, State state) {
        return analyzeInternal(history, attackTick, List.of(target), 0, 0, state);
    }

    /**
     * Same judgment, but tries every attacker aim sample from {@link #TOLERANCE_SAMPLES_BEFORE}
     * before to {@link #TOLERANCE_SAMPLES_AFTER} after the attack against every supplied target
     * snapshot, and only counts a miss if NONE of them touches the true box - the real aim and the
     * real target position at the instant of the click both lie inside that neighbourhood, so a
     * genuine hit can no longer look like a miss just because the nearest packet was a tick stale.
     * Needs the evaluation deferred a couple of ticks so the "after" samples exist.
     */
    public double analyzeWithTolerance(Deque<MovementSample> history, long attackTick,
                                       List<PositionSnapshot> targets, State state) {
        return analyzeInternal(history, attackTick, targets, TOLERANCE_SAMPLES_BEFORE, TOLERANCE_SAMPLES_AFTER, state);
    }

    private double analyzeInternal(Deque<MovementSample> history, long attackTick, List<PositionSnapshot> targets,
                                   int samplesBefore, int samplesAfter, State state) {
        List<MovementSample> ordered = List.copyOf(history);
        int attackIndex = AimGeometry.indexOfClosest(ordered, attackTick);
        if (attackIndex < 0 || targets.isEmpty()) {
            return 0.0;
        }
        MovementSample atAttack = ordered.get(attackIndex);
        PositionSnapshot target = targets.get(0);
        double eyeY = atAttack.y() + AimGeometry.EYE_HEIGHT;
        double[] direction = AimGeometry.facingVector(atAttack.yaw(), atAttack.pitch());
        boolean intersectsTrueHitbox = false;
        int from = Math.max(0, attackIndex - samplesBefore);
        int to = Math.min(ordered.size() - 1, attackIndex + samplesAfter);
        for (int i = from; i <= to && !intersectsTrueHitbox; i++) {
            MovementSample sample = ordered.get(i);
            double sampleEyeY = sample.y() + AimGeometry.EYE_HEIGHT;
            double[] sampleDirection = AimGeometry.facingVector(sample.yaw(), sample.pitch());
            for (PositionSnapshot candidate : targets) {
                if (AimGeometry.rayIntersectsBox(sample.x(), sampleEyeY, sample.z(), sampleDirection, candidate,
                        MAX_RAY_DISTANCE_BLOCKS, RAY_PADDING_BLOCKS)) {
                    intersectsTrueHitbox = true;
                    break;
                }
            }
        }
        double eyeToBoxDistance = target.distanceTo(atAttack.x(), eyeY, atAttack.z());
        LOG.log(Level.INFO, "Hitboxes: attacker eye ({0}, {1}, {2}) facing ({3}, {4}, {5}), target box center "
                        + "({6}, {7}, {8}) halfWidth {9} height {10}, eye-to-box distance {11}, intersects {12}",
                new Object[] {atAttack.x(), eyeY, atAttack.z(), direction[0], direction[1], direction[2],
                        target.x(), target.y(), target.z(), target.halfWidth(), target.height(),
                        eyeToBoxDistance, intersectsTrueHitbox});
        state.record(!intersectsTrueHitbox);

        List<Boolean> recent = state.recent();
        if (recent.size() < MIN_SAMPLES) {
            return 0.0; // not enough attacks yet to tell a pattern from one backtrack artifact
        }
        long missCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = (double) missCount / recent.size();
        if (fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
