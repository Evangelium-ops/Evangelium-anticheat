package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a scaffold hack that fakes a plausible look-angle for every placement (defeating {@link
 * ScaffoldAnalyzer}) and paces itself under any rate threshold (defeating {@link
 * ScaffoldSpeedCheck}) - confirmed live against a real such hack, which evaded both of those
 * signals cleanly. This one is mechanism-based instead: a legitimate manual bridge is only
 * practical while SNEAKING, since that's the only way a human can stand still at an edge long
 * enough to aim and place the block beneath their own feet without simply falling first. A
 * scaffold hack has no such constraint - it isn't relying on standing still to avoid falling, so it
 * has no reason to sneak, and most don't bother faking it.
 */
public final class ScaffoldSneakAnalyzer {

    private static final Logger LOG = Logger.getLogger(ScaffoldSneakAnalyzer.class.getName());
    // Generous over a player's real 0.3-block hitbox half-width - covers placing directly beneath
    // or just behind their own feet as they walk, without being so wide it catches ordinary nearby
    // building.
    private static final double SELF_PLACEMENT_HORIZONTAL_RADIUS_BLOCKS = 1.0;
    // A self-supporting placement lands at or a little below current feet level - generous
    // padding for tick-snap the same way every other constant in this project is.
    private static final double SELF_PLACEMENT_VERTICAL_TOLERANCE_BLOCKS = 2.0;
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    private static final double SUSPICIOUS_FRACTION = 0.5;

    public static final class State {
        private final Deque<Boolean> recentSuspicious = new ArrayDeque<>();

        void record(boolean suspicious) {
            recentSuspicious.addLast(suspicious);
            while (recentSuspicious.size() > HISTORY_SIZE) {
                recentSuspicious.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentSuspicious);
        }
    }

    public double analyze(MovementSample placerLast, int blockX, int blockY, int blockZ, State state) {
        double centerX = blockX + 0.5;
        double centerZ = blockZ + 0.5;
        double dx = centerX - placerLast.x();
        double dz = centerZ - placerLast.z();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double verticalDelta = placerLast.y() - blockY; // positive: block is at/below feet level

        boolean wouldSupportSelf = horizontalDistance <= SELF_PLACEMENT_HORIZONTAL_RADIUS_BLOCKS
                && verticalDelta >= 0.0 && verticalDelta <= SELF_PLACEMENT_VERTICAL_TOLERANCE_BLOCKS;
        boolean suspicious = wouldSupportSelf && !placerLast.effects().sneaking();
        state.record(suspicious);

        List<Boolean> recent = state.recent();
        long suspiciousCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) suspiciousCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "ScaffoldSneak: horizontalDistance {0}, verticalDelta {1}, wouldSupportSelf {2}, "
                        + "sneaking {3}, suspicious {4}, samples {5}, recent fraction {6}",
                new Object[] {horizontalDistance, verticalDelta, wouldSupportSelf, placerLast.effects().sneaking(),
                        suspicious, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }
}
