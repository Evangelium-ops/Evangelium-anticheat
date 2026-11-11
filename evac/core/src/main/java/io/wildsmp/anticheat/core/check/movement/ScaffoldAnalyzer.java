package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a scaffold/auto-bridge hack - a client-side module that places blocks under the player's
 * feet automatically as they walk off an edge, faster and more precisely than a human manually
 * placing while also watching their footing. Does the real geometry a vanilla client's own
 * placement raycast requires: the placed block has to actually be within the player's real look
 * direction and reach, not just conveniently wherever their feet need support. A scaffold hack
 * generally doesn't bother perfectly faking look-alignment for every placement - simulating a
 * believable "glance down while walking forward" for every single block is a much harder problem
 * than placing the block itself.
 */
public final class ScaffoldAnalyzer {

    private static final Logger LOG = Logger.getLogger(ScaffoldAnalyzer.class.getName());
    private static final double EYE_HEIGHT = 1.62;
    // Vanilla's real block-interaction reach is ~4.5 blocks in survival (6.0 in creative) - padded
    // generously above the larger of those, matching this project's "real fact, then tolerance"
    // approach everywhere else.
    private static final double MAX_PLACEMENT_REACH_BLOCKS = 7.0;
    // How far off-center a legitimate placement's aim can land while still counting as "looking at
    // it" - a human doesn't perfectly center their crosshair on the exact block face they're
    // placing against, especially mid-bridge while also watching their footing. Generous on
    // purpose - unverified against real playtest data yet, may need retuning the way ReachA's
    // tolerance did.
    private static final double MAX_LEGITIMATE_ANGLE_DEGREES = 30.0;
    private static final int HISTORY_SIZE = 8;
    private static final int MIN_SAMPLES = 3;
    // A majority of recent placements missing real look-alignment is the actual tell - one could be
    // a genuine quick flick or backtrack artifact, same reasoning as HitboxAnalyzer's.
    private static final double SUSPICIOUS_FRACTION = 0.5;

    public static final class State {
        private final Deque<Boolean> recentMisaligned = new ArrayDeque<>();

        void record(boolean misaligned) {
            recentMisaligned.addLast(misaligned);
            while (recentMisaligned.size() > HISTORY_SIZE) {
                recentMisaligned.removeFirst();
            }
        }

        List<Boolean> recent() {
            return List.copyOf(recentMisaligned);
        }
    }

    /**
     * @param placerLast the placer's most recent movement sample (position/rotation at or nearest
     *                    the moment of placement)
     * @param blockX      the placed block's integer world X
     * @param blockY      the placed block's integer world Y
     * @param blockZ      the placed block's integer world Z
     * @param state       this player's rolling analyzer state
     */
    public double analyze(MovementSample placerLast, int blockX, int blockY, int blockZ, State state) {
        double eyeX = placerLast.x();
        double eyeY = placerLast.y() + EYE_HEIGHT;
        double eyeZ = placerLast.z();
        double centerX = blockX + 0.5;
        double centerY = blockY + 0.5;
        double centerZ = blockZ + 0.5;

        double dx = centerX - eyeX;
        double dy = centerY - eyeY;
        double dz = centerZ - eyeZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double[] facing = facingVector(placerLast.yaw(), placerLast.pitch());
        double angleDegrees;
        if (distance < 1e-6) {
            angleDegrees = 0.0; // degenerate - placer's eye is exactly at the block's center
        } else {
            double dot = (facing[0] * dx + facing[1] * dy + facing[2] * dz) / distance;
            angleDegrees = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        }

        boolean misaligned = distance > MAX_PLACEMENT_REACH_BLOCKS || angleDegrees > MAX_LEGITIMATE_ANGLE_DEGREES;
        state.record(misaligned);

        List<Boolean> recent = state.recent();
        long misalignedCount = recent.stream().filter(Boolean::booleanValue).count();
        double fraction = recent.isEmpty() ? 0.0 : (double) misalignedCount / recent.size();
        LOG.log(fraction >= SUSPICIOUS_FRACTION ? Level.INFO : Level.FINE, "Scaffold: distance {0}, angleDegrees {1}, misaligned {2}, samples {3}, recent fraction {4}",
                new Object[] {distance, angleDegrees, misaligned, recent.size(), fraction});

        if (recent.size() < MIN_SAMPLES || fraction < SUSPICIOUS_FRACTION) {
            return 0.0;
        }
        return Math.min(1.0, (fraction - SUSPICIOUS_FRACTION) / (1.0 - SUSPICIOUS_FRACTION));
    }

    private static double[] facingVector(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new double[] {x, y, z};
    }
}
