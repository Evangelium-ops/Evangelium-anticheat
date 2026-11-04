package io.wildsmp.anticheat.core.check.combat;

/**
 * A player's reconstructed position and bounding box at a specific server tick.
 */
public record PositionSnapshot(double x, double y, double z, double halfWidth, double height, long tick) {

    /** Distance from the given point to the closest point on this snapshot's axis-aligned bounding box. */
    public double distanceTo(double px, double py, double pz) {
        double minX = x - halfWidth;
        double maxX = x + halfWidth;
        double minY = y;
        double maxY = y + height;
        double minZ = z - halfWidth;
        double maxZ = z + halfWidth;

        double clampedX = clamp(px, minX, maxX);
        double clampedY = clamp(py, minY, maxY);
        double clampedZ = clamp(pz, minZ, maxZ);

        double dx = px - clampedX;
        double dy = py - clampedY;
        double dz = pz - clampedZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Straight-line distance between this snapshot's and {@code other}'s center points. */
    public double centerDistanceTo(PositionSnapshot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
