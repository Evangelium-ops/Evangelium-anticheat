package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects climbing a vertical surface with no real {@link TerrainKind#CLIMBABLE} block (ladder,
 * vine, scaffolding) explaining it. Tracks the horizontal position where an unsupported ascent
 * began and requires the whole climb to stay pressed against roughly that same spot - genuine
 * flight (already {@link FlyAnalyzer}'s territory) tends to drift; climbing a specific wall face
 * doesn't. A legitimate jump is naturally covered by this too (its own rise stays under the jump
 * ceiling regardless of horizontal drift, so it never accumulates enough sustained "climbing"
 * evidence to flag). Exempts a player with {@link MovementEffects#flightAllowed()} entirely -
 * creative/spectator/plugin-granted flight can climb a wall face legitimately too. A server-driven launch
 * ({@link MovementEffects#recentlyLaunched()}: mob knockback, wind charge, riptide...) rises faster than a
 * jump with no climbable block under it, so that whole arc is exempt too.
 */
public final class SpiderAnalyzer {

    private static final Logger LOG = Logger.getLogger(SpiderAnalyzer.class.getName());
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    private static final double SUSPICIOUS_FRACTION = 0.5;
    // Generous radius around the climb's starting horizontal position - real wall-climbing hugs a
    // single wall face; this tolerates minor strafing along it without excusing genuinely flying
    // away from the wall.
    private static final double MAX_HORIZONTAL_DRIFT_BLOCKS = 1.5;
    // Padding on top of the jump ceiling for a single tick's rise - matches FlyAnalyzer's own
    // tolerance reasoning.
    private static final double RISE_TOLERANCE = 0.1;

    private final PredictedMovementEngine engine;

    public SpiderAnalyzer(PredictedMovementEngine engine) {
        this.engine = engine;
    }

    public static final class State {
        private final Deque<Boolean> recentExcessive = new ArrayDeque<>();
        private long ticksAirborne = 0;
        // the upward velocity a jump started at the first unsupported tick would still have (decays like gravity says)
        private double trackedVelocity = 0.0;
        private double originX;
        private double originZ;

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
        boolean inLiquid = current.terrain() == TerrainKind.LIQUID_WATER || current.terrain() == TerrainKind.LIQUID_LAVA
                || current.terrain() == TerrainKind.SUBMERGED;
        boolean supported = current.onGround() || current.terrain() == TerrainKind.CLIMBABLE || inLiquid
                || current.effects().swimming() || current.effects().gliding() || current.effects().levitation()
                || current.effects().flightAllowed() || current.effects().recentlyLaunched();
        if (supported) {
            state.ticksAirborne = 0;
            state.trackedVelocity = 0.0;
            // See FlyAnalyzer: a supported tick is clean evidence, so stale suspicious samples fade instead of lingering.
            state.record(false);
            return 0.0;
        }
        boolean justJumped = (previous.onGround() && !current.onGround()) || state.ticksAirborne == 0;
        if (state.ticksAirborne == 0) {
            state.originX = previous.x();
            state.originZ = previous.z();
        }
        if (justJumped) {
            state.trackedVelocity = engine.maxVerticalRise(current.effects(), true);
        } else {
            state.trackedVelocity = (state.trackedVelocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
        }
        state.ticksAirborne++;

        double dy = current.y() - previous.y();
        // a jump of any height (Jump Boost included) rises along a decaying arc; only rising faster than that arc says
        // "climbing". Before this the ceiling was a flat 0.1 after the first tick, so every high jump read as wall-climbing.
        double jumpCeiling = Math.max(0.0, state.trackedVelocity) + RISE_TOLERANCE;

        double driftX = current.x() - state.originX;
        double driftZ = current.z() - state.originZ;
        double horizontalDrift = Math.sqrt(driftX * driftX + driftZ * driftZ);
        boolean stayingOnTheSameWall = horizontalDrift <= MAX_HORIZONTAL_DRIFT_BLOCKS;

        boolean excessive = dy > jumpCeiling && stayingOnTheSameWall;
        state.record(excessive);

        List<Boolean> recent = state.recent();
        long excessiveCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) excessiveCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "Spider: dy {0}, jumpCeiling {1}, horizontalDrift {2}, excessive {3}, "
                        + "samples {4}, recent fraction {5}",
                new Object[] {dy, jumpCeiling, horizontalDrift, excessive, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
