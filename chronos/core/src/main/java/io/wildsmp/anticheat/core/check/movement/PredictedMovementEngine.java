package io.wildsmp.anticheat.core.check.movement;

/**
 * Computes generous per-tick movement ceilings from {@link VanillaPhysics}'s real, padded
 * constants and a player's current {@link TerrainKind}/{@link MovementEffects} - the shared
 * foundation every check in this package compares a player's actual reported movement against.
 * Returns ceilings (maximum plausible values), never a single predicted point - see the design
 * spec's rationale for why a ceiling-based model is the right one here, matching every other
 * physics-based check already built in this project.
 */
public final class PredictedMovementEngine {

    public double maxHorizontalDisplacement(TerrainKind terrain, MovementEffects effects) {
        double base = switch (terrain) {
            case ICE, PACKED_ICE -> VanillaPhysics.MAX_ICE_SPEED_PER_TICK;
            case LIQUID_WATER, LIQUID_LAVA -> VanillaPhysics.MAX_LIQUID_SURFACE_SPEED_PER_TICK;
            case CLIMBABLE -> VanillaPhysics.MAX_CLIMB_SPEED_PER_TICK;
            default -> effects.sprinting() ? VanillaPhysics.MAX_SPRINT_SPEED_PER_TICK : VanillaPhysics.MAX_WALK_SPEED_PER_TICK;
        };
        double speedMultiplier = 1.0
                + effects.speedLevel() * VanillaPhysics.POTION_SPEED_MULTIPLIER_PER_LEVEL
                - effects.slownessLevel() * VanillaPhysics.POTION_SPEED_MULTIPLIER_PER_LEVEL;
        return base * Math.max(0.1, speedMultiplier);
    }

    public double maxVerticalRise(MovementEffects effects, boolean justJumped) {
        if (!justJumped) {
            return 0.0;
        }
        return VanillaPhysics.BASE_JUMP_VELOCITY + effects.jumpBoostLevel() * VanillaPhysics.JUMP_VELOCITY_PER_LEVEL_BONUS;
    }

    /** @return how far DOWN (a positive magnitude) a player may legitimately have fallen this tick, given how long they've been airborne. */
    public double maxVerticalDescent(long ticksAirborne) {
        double velocity = 0.0;
        double distance = 0.0;
        for (long tick = 0; tick < ticksAirborne; tick++) {
            velocity = (velocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
            distance = Math.abs(velocity);
        }
        return distance;
    }
}
