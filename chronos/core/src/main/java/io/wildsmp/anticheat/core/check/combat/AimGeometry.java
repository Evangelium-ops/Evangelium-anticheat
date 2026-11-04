package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.player.MovementSample;

import java.util.List;

/** Shared aim-angle math used by {@link AimPrecisionAnalyzer} and {@link SilentAimAnalyzer}. */
final class AimGeometry {

    static final double EYE_HEIGHT = 1.62;

    private AimGeometry() {
    }

    static int indexOfClosest(List<MovementSample> ordered, long tick) {
        int best = -1;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < ordered.size(); i++) {
            long diff = Math.abs(ordered.get(i).tickTimestamp() - tick);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /** Angle in degrees between where {@code attacker} was facing and the true direction to {@code target}'s hitbox. */
    static double angularErrorDegrees(MovementSample attacker, PositionSnapshot target) {
        double[] facing = facingVector(attacker.yaw(), attacker.pitch());
        double[] toTarget = requiredDirection(attacker, target);
        if (toTarget[0] == 0.0 && toTarget[1] == 0.0 && toTarget[2] == 0.0) {
            return 0.0; // degenerate - attacker's eye is exactly at the target's reference point
        }

        double dot = facing[0] * toTarget[0] + facing[1] * toTarget[1] + facing[2] * toTarget[2];
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    /**
     * True if a single fixed aim direction - {@code attacker}'s own actual facing, held constant -
     * touches the target's hitbox at exactly one of {@code targetStart}/{@code targetEnd}, not both
     * (and not neither). That coverage flip is what "the target moved enough that a human would
     * need to actually adjust their camera to keep landing hits" really means: if the same frozen
     * ray still touches the target's (1.8-block-tall, generously padded) hitbox at both ends of the
     * window, nothing about the target's position required any tracking, no matter how much its
     * exact center point wandered vertically - which is exactly what ordinary knockback bounce and
     * jump-crit bobbing (on the attacker's side OR the target's) do on every single hit. Confirmed
     * live: measuring the angle to the target's center point instead of testing real ray-vs-hitbox
     * coverage kept reading that ordinary vertical wobble as "needed tracking," on both sides of the
     * fight, no matter how the threshold was tuned.
     */
    static boolean requiredTrackingAcrossWindow(MovementSample attacker, PositionSnapshot targetStart,
                                                 PositionSnapshot targetEnd, double maxDistance, double paddingBlocks) {
        double eyeY = attacker.y() + EYE_HEIGHT;
        double[] direction = facingVector(attacker.yaw(), attacker.pitch());
        boolean hitsStart = rayIntersectsBox(attacker.x(), eyeY, attacker.z(), direction, targetStart, maxDistance, paddingBlocks);
        boolean hitsEnd = rayIntersectsBox(attacker.x(), eyeY, attacker.z(), direction, targetEnd, maxDistance, paddingBlocks);
        return hitsStart != hitsEnd;
    }

    private static double[] requiredDirection(MovementSample attacker, PositionSnapshot target) {
        double eyeX = attacker.x();
        double eyeY = attacker.y() + EYE_HEIGHT;
        double eyeZ = attacker.z();
        return normalize(target.x() - eyeX, target.y() + target.height() * 0.5 - eyeY, target.z() - eyeZ);
    }

    static double[] facingVector(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        return new double[] {x, y, z};
    }

    /**
     * True if a ray from the given origin in the given (normalized) direction actually intersects
     * {@code target}'s true bounding box - padded by {@code paddingBlocks} on every side - within
     * {@code maxDistance}. The real geometry a vanilla client's own raycast requires for a hit to
     * register is exact, unpadded, but our own attacker-position sampling isn't: it's only as
     * precise as the nearest recorded tick, and during fast vertical motion (a jump-crit's rise
     * and fall) that tick-snap error translates into a bigger swing in the ray's actual origin than
     * it does during normal ground combat where Y barely changes tick to tick. A zero-tolerance
     * test flags genuinely well-aimed jump-crit hits for exactly that reason - confirmed live.
     */
    static boolean rayIntersectsBox(double originX, double originY, double originZ, double[] direction,
                                     PositionSnapshot target, double maxDistance, double paddingBlocks) {
        double[] origin = {originX, originY, originZ};
        double[] min = {target.x() - target.halfWidth() - paddingBlocks, target.y() - paddingBlocks,
                target.z() - target.halfWidth() - paddingBlocks};
        double[] max = {target.x() + target.halfWidth() + paddingBlocks, target.y() + target.height() + paddingBlocks,
                target.z() + target.halfWidth() + paddingBlocks};

        double tMin = 0.0;
        double tMax = maxDistance;
        for (int axis = 0; axis < 3; axis++) {
            double o = origin[axis];
            double d = direction[axis];
            double lo = min[axis];
            double hi = max[axis];
            if (Math.abs(d) < 1e-9) {
                // Ray parallel to this axis - only possibly intersects if already within the slab.
                if (o < lo || o > hi) {
                    return false;
                }
                continue;
            }
            double t1 = (lo - o) / d;
            double t2 = (hi - o) / d;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return false;
            }
        }
        return true;
    }

    private static double[] normalize(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len == 0.0) {
            return new double[] {0.0, 0.0, 0.0};
        }
        return new double[] {x / len, y / len, z / len};
    }
}
