package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects horizontal movement across the surface of a liquid without real swim physics applying -
 * vanilla has no "walk on water" mechanic at all, so ANY meaningful horizontal displacement while
 * standing on {@link TerrainKind#LIQUID_WATER}/{@link TerrainKind#LIQUID_LAVA} without {@link
 * MovementEffects#swimming()} is already suspicious, not a matter of exceeding some speed ceiling.
 */
public final class JesusAnalyzer {

    private static final Logger LOG = Logger.getLogger(JesusAnalyzer.class.getName());
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    private static final double SUSPICIOUS_FRACTION = 0.5;
    // Generous "standing still" tolerance - a player at a water's edge with a foot in the water,
    // or natural current-driven drift, shouldn't flag just for existing near/in liquid.
    private static final double STATIONARY_TOLERANCE_BLOCKS = 0.05;

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

    public double analyze(MovementSample previous, MovementSample current, State state) {
        boolean onLiquidSurface = (current.terrain() == TerrainKind.LIQUID_WATER || current.terrain() == TerrainKind.LIQUID_LAVA)
                && current.onGround() && !current.effects().swimming();
        if (!onLiquidSurface || current.effects().recentlyLaunched() || current.effects().flightAllowed()) {
            return 0.0; // not the relevant situation at all - not evidence either way
        }

        double dx = current.x() - previous.x();
        double dz = current.z() - previous.z();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        boolean excessive = horizontalDistance > STATIONARY_TOLERANCE_BLOCKS;
        state.record(excessive);

        List<Boolean> recent = state.recent();
        long excessiveCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) excessiveCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "Jesus: distance {0}, excessive {1}, samples {2}, recent fraction {3}",
                new Object[] {horizontalDistance, excessive, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
