package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects sustained horizontal movement faster than {@link PredictedMovementEngine}'s ceiling for
 * the player's actual terrain/effects at the time - not a flat speed limit, since legitimate max
 * speed varies a lot by surface (ice) and effects (Speed potion).
 *
 * <p>Only judges GROUNDED movement (or climbing/swimming, whose own terrain ceilings already
 * apply) - a player who is airborne and unsupported is {@link FlyAnalyzer}'s territory, not this
 * one's. Two reasons: (1) vanilla's real sprint-jump mechanic gives a genuine horizontal boost the
 * instant a sprinting player leaves the ground, which this analyzer has no way to distinguish from
 * a real horizontal speed hack without duplicating Fly's own jump-legitimacy logic; (2) a real fly
 * hack that also moves fast horizontally would otherwise flag BOTH SpeedA and FlyA for the same
 * violation - each cheat category should have exactly one owning check for a given signal, the way
 * SpiderA's climb-in-place signal is kept separate from FlyA's generic airborne-too-long one.
 */
public final class SpeedAnalyzer {

    private static final Logger LOG = Logger.getLogger(SpeedAnalyzer.class.getName());
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    private static final double SUSPICIOUS_FRACTION = 0.5;
    // Ping-proportional padding on top of the physics ceiling itself - real network jitter can
    // make consecutive samples land slightly further apart than the last tick's true position
    // alone would suggest. Unverified against real playtest data yet - may need retuning the way
    // ReachA's tolerance did.
    private static final double PING_TOLERANCE_PER_TICK_LAG = 0.05;

    private final PredictedMovementEngine engine;

    public SpeedAnalyzer(PredictedMovementEngine engine) {
        this.engine = engine;
    }

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
     * @param previous          the sample immediately before {@code current}
     * @param current            the sample being judged
     * @param estimatedTickLag   this player's current {@code TickSyncTracker} lag estimate
     * @param state              this player's rolling analyzer state
     */
    public double analyze(MovementSample previous, MovementSample current, double estimatedTickLag, State state) {
        // A server-driven impulse (plugin dash/launch, knockback-free teleport landing) or a
        // player the server has allowed to fly makes any displacement legitimate for as long as
        // the bukkit side keeps these flags set - not evidence either way, and recording it would
        // poison the rolling history with samples that were never suspicious.
        if (current.effects().recentlyLaunched() || current.effects().flightAllowed()) {
            return 0.0;
        }
        boolean airborneAndUnsupported = !current.onGround() && current.terrain() != TerrainKind.CLIMBABLE
                && !current.effects().swimming();
        if (airborneAndUnsupported) {
            return 0.0; // FlyA's territory - not evidence either way for Speed
        }

        double dx = current.x() - previous.x();
        double dz = current.z() - previous.z();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        double ceiling = engine.maxHorizontalDisplacement(current.terrain(), current.effects())
                + estimatedTickLag * PING_TOLERANCE_PER_TICK_LAG;
        boolean excessive = horizontalDistance > ceiling;
        state.record(excessive);

        List<Boolean> recent = state.recent();
        long excessiveCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) excessiveCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "Speed: distance {0}, ceiling {1}, excessive {2}, samples {3}, recent fraction {4}",
                new Object[] {horizontalDistance, ceiling, excessive, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
