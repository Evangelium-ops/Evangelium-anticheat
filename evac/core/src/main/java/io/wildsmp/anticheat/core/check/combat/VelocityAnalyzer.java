package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a "velocity" cheat - a module that alters how much knockback actually gets applied when
 * a player is hit, most commonly to pop the victim upward far more than a real hit ever could (a
 * tactical escape/repositioning tool, not just a visual glitch).
 *
 * <p>Confirmed live via a real cheat trajectory dump: this does NOT compare a single tick's
 * velocity. Vanilla's true vertical knockback ceiling is a fixed INITIAL velocity of 0.4
 * blocks/tick (regardless of weapon, enchant level, or sprint status - only the horizontal
 * component scales with those), but that initial velocity decays under gravity across several
 * ticks before the victim starts falling again, so the real quantity that matters is the TOTAL
 * peak height gained above their last grounded position - working through vanilla's actual
 * gravity/drag decay from a 0.4 initial velocity, that comes out to roughly 1.15 blocks of total
 * rise, never more, under any legitimate combination. A live capture of the actual cheat showed a
 * clean, textbook parabolic arc peaking 3.3 blocks above the launch point - about 3x the vanilla
 * ceiling.
 *
 * <p>This deliberately does NOT attempt to verify HORIZONTAL knockback magnitude/direction, or
 * detect knockback REDUCTION ("no-knockback") - both would need the attacker's sprint state and
 * weapon/enchant level at the moment of the hit, which aren't tracked anywhere in this codebase
 * yet. This only catches the vertical "pop" case, which is a hard physics ceiling and needs no
 * extra per-hit context to check safely.
 */
public final class VelocityAnalyzer {

    private static final Logger LOG = Logger.getLogger(VelocityAnalyzer.class.getName());

    /** Sentinel returned while the victim's bounce arc hasn't resolved (landed, or timed out) yet. */
    public static final double UNRESOLVED = Double.NaN;

    // ~1.15 blocks is the real total peak rise a 0.4 initial vertical velocity produces under
    // vanilla gravity/drag decay (each tick: v = (v - 0.08) * 0.98, summing positive position
    // deltas until v goes negative). Doubled for a generous safety margin against imprecision in
    // that derivation - a live-captured real cheat trajectory peaked at 3.3 blocks, well clear of
    // even this padded ceiling. Unverified against a wider range of real playtest data yet - may
    // need retuning the way ReachA's tolerance did.
    private static final double VANILLA_MAX_PEAK_RISE_BLOCKS = 2.0;
    // How long to keep watching an airborne victim before giving up and judging whatever peak was
    // reached so far - a real bounce (legitimate or not) lands well within this; this is just a
    // safety valve against a victim who never touches the ground again within this window (flight,
    // falling into a ravine, disconnecting).
    private static final int MAX_TRAJECTORY_WINDOW_TICKS = 60; // 3s
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    // A majority of recent hits producing an impossible peak rise is the actual tell - one could be
    // a measurement artifact (a resync, a teleport landing mid-arc).
    private static final double SUSPICIOUS_FRACTION = 0.5;

    /** Per-player rolling state this analyzer needs across many hits - stash via PlayerData's check-state map. */
    public static final class State {
        private final Deque<Boolean> recentExcessive = new ArrayDeque<>();

        void record(boolean excessive) {
            recentExcessive.addLast(excessive);
            while (recentExcessive.size() > HISTORY_SIZE) {
                recentExcessive.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentExcessive);
        }
    }

    /**
     * @param targetHistory the VICTIM's own recent movement history, oldest first - this measures
     *                      what actually happened to the person who got hit, not the attacker
     * @param hitTick       the tick the attack landed
     * @param state         this victim's rolling analyzer state, stashed via {@code
     *                      PlayerData.computeCheckState} on the VICTIM's own PlayerData
     * @return {@link #UNRESOLVED} if the victim's post-hit arc hasn't landed (or timed out) yet -
     *         call again on a later tick once more history has accumulated - otherwise a final
     *         suspicion score from 0.0 (normal or no knockback) to 1.0 (sustained impossible peak
     *         height)
     */
    public double analyze(Deque<MovementSample> targetHistory, long hitTick, State state) {
        List<MovementSample> ordered = List.copyOf(targetHistory);
        int hitIndex = AimGeometry.indexOfClosest(ordered, hitTick);
        if (hitIndex < 0) {
            return 0.0; // no history at all near the hit - nothing to measure
        }

        int baselineIndex = hitIndex;
        while (baselineIndex > 0 && !ordered.get(baselineIndex).onGround()) {
            baselineIndex--;
        }
        if (!ordered.get(baselineIndex).onGround()) {
            return 0.0; // never grounded anywhere in the retained window - can't establish a baseline
        }
        double baselineY = ordered.get(baselineIndex).y();

        double peakY = ordered.get(hitIndex).y();
        boolean landedAgain = false;
        int lastIndexSeen = hitIndex;
        for (int i = hitIndex + 1; i < ordered.size(); i++) {
            MovementSample sample = ordered.get(i);
            peakY = Math.max(peakY, sample.y());
            lastIndexSeen = i;
            if (sample.onGround()) {
                landedAgain = true;
                break;
            }
        }
        long ticksSinceHit = ordered.get(lastIndexSeen).tickTimestamp() - hitTick;
        if (!landedAgain && ticksSinceHit < MAX_TRAJECTORY_WINDOW_TICKS) {
            return UNRESOLVED; // still airborne and within the patience window - not enough data yet
        }

        double peakRise = peakY - baselineY;
        boolean excessive = peakRise > VANILLA_MAX_PEAK_RISE_BLOCKS;
        state.record(excessive);

        List<Boolean> recent = state.recent();
        long excessiveCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) excessiveCount / recent.size();
        LOG.log(Level.INFO, "Velocity: hit tick {0}, baseline {1}, peak {2}, rise {3}, landed {4}, "
                        + "excessive {5}, samples {6}, recent fraction {7}",
                new Object[] {hitTick, baselineY, peakY, peakRise, landedAgain, excessive, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
