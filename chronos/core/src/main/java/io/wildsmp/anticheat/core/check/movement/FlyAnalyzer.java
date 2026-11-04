package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects sustained unsupported height gain - not a single-tick rise ceiling (a real jump's rise
 * decelerates tick by tick under gravity/drag, so a static ceiling would false-flag a jump's own
 * later ticks), but the player's actual rise compared against a per-player TRACKED vertical
 * velocity estimate, seeded from {@link PredictedMovementEngine#maxVerticalRise} the tick they
 * leave the ground and decayed the same way vanilla physics decays it every airborne tick after.
 * Also flags simply being airborne (not grounded, not climbing, not swimming/gliding/levitating)
 * far longer than any real jump arc could last, regardless of single-tick rise - a slow, steady
 * hover is still a fly hack even if no individual tick looks dramatic. Exempts a player with
 * {@link MovementEffects#flightAllowed()} entirely - creative/spectator/plugin-granted flight is
 * legitimate, not evidence of anything - and likewise exempts {@link
 * MovementEffects#recentlyLaunched()} for a player's whole flight/fall arc after a real
 * server-driven velocity impulse (wind charge, explosion, engine push).
 */
public final class FlyAnalyzer {

    private static final Logger LOG = Logger.getLogger(FlyAnalyzer.class.getName());
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    private static final double SUSPICIOUS_FRACTION = 0.5;
    // Generous padding on top of the tracked velocity estimate for tick-snap/rounding - not a
    // guess at real physics, just tolerance around it, same reasoning as every other padded
    // constant in this project.
    private static final double VELOCITY_TOLERANCE = 0.1;
    // A real jump's full rise-then-fall arc, even with the maximum plausible Jump Boost level this
    // project models, comfortably finishes well within this many ticks - padded generously above
    // the real ~17-18 tick observed vanilla maximum. Sustained unbroken airborne time beyond this
    // is itself the fly signature, independent of any single tick's rise.
    private static final long MAX_LEGITIMATE_AIRBORNE_TICKS = 40;

    private final PredictedMovementEngine engine;

    public FlyAnalyzer(PredictedMovementEngine engine) {
        this.engine = engine;
    }

    public static final class State {
        private final Deque<Boolean> recentExcessive = new ArrayDeque<>();
        private long ticksAirborne = 0;
        private double trackedVelocity = 0.0;

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

    public double analyze(MovementSample previous, MovementSample current, State state) {
        // A status-only packet (the client re-sending its onGround flag with no movement) reaches us as a second sample for the
        // same server tick at the exact same spot. No physics step happened, so it is not evidence either way - and counting it
        // would advance the airborne clock and read as "stopped rising" in the middle of a jump.
        if (current.tickTimestamp() == previous.tickTimestamp() && current.x() == previous.x() && current.y() == previous.y()
                && current.z() == previous.z()) {
            return 0.0;
        }
        // In water or lava the player is held up by the liquid, not "airborne": treading water or wading is not sprint-swimming
        // (effects.swimming only means that), so without this every ordinary swimmer looked like a flyer. Slow Falling makes
        // the descent far slower than the gravity model predicts, which would otherwise read as rising.
        boolean inLiquid = current.terrain() == TerrainKind.LIQUID_WATER || current.terrain() == TerrainKind.LIQUID_LAVA
                || current.terrain() == TerrainKind.SUBMERGED;
        boolean supported = current.onGround() || current.terrain() == TerrainKind.CLIMBABLE || inLiquid
                || current.effects().swimming() || current.effects().gliding() || current.effects().levitation()
                || current.effects().slowFalling()
                || current.effects().flightAllowed() || current.effects().recentlyLaunched();
        if (supported) {
            state.ticksAirborne = 0;
            state.trackedVelocity = 0.0;
            // A supported tick is clean evidence: without recording it, suspicious samples from an earlier moment stay in the
            // recent history for as long as the player keeps standing on something, and one or two odd ticks later (the first tick
            // of leaving water, a vine, a block edge) tipped it over the flag line.
            state.record(false);
            return 0.0;
        }

        boolean justJumped = (previous.onGround() && !current.onGround()) || state.ticksAirborne == 0;
        if (justJumped) {
            state.trackedVelocity = engine.maxVerticalRise(current.effects(), true);
        } else if (state.ticksAirborne > 0) {
            state.trackedVelocity = (state.trackedVelocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
        }
        // else: airborne with no jump-transition ever seen (history starts mid-air) - trackedVelocity
        // stays at its default 0.0, which is exactly right: a real player wouldn't be gaining height
        // from a standing start with zero initial velocity.
        state.ticksAirborne++;

        double dy = current.y() - previous.y();
        boolean risingTooFast = dy > state.trackedVelocity + VELOCITY_TOLERANCE;
        boolean airborneTooLong = state.ticksAirborne > MAX_LEGITIMATE_AIRBORNE_TICKS;
        boolean excessive = risingTooFast || airborneTooLong;
        state.record(excessive);

        List<Boolean> recent = state.recent();
        long excessiveCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) excessiveCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "Fly: dy {0}, trackedVelocity {1}, ticksAirborne {2}, risingTooFast {3}, "
                        + "airborneTooLong {4}, samples {5}, recent fraction {6}, terrain {7}, onGround {8}, swimming {9}, "
                        + "launched {10}, jumpBoost {11}",
                new Object[] {dy, state.trackedVelocity, state.ticksAirborne, risingTooFast, airborneTooLong,
                        recent.size(), fraction, current.terrain(), current.onGround(), current.effects().swimming(),
                        current.effects().recentlyLaunched(), current.effects().jumpBoostLevel()});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
