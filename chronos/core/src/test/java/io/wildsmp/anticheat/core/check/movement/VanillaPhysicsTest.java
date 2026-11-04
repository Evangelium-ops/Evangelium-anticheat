package io.wildsmp.anticheat.core.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaPhysicsTest {

    @Test
    void simulatingGravityAloneMatchesTheWellKnownTerminalDescentPattern() {
        // A real player falling with no other forces reaches roughly -3.92 blocks/tick terminal
        // velocity (gravity/drag reach equilibrium) - this is a widely-documented, independently
        // verifiable fact, not something derived from this codebase, and is what every NoFallA/
        // FlyA ceiling ultimately gets checked against.
        double velocity = 0.0;
        // Convergence to the -3.92 fixed point is geometric with ratio AIR_DRAG (0.98) per tick -
        // 200 ticks only gets within ~0.07 of the true fixed point, not the 0.05 this test wants;
        // 400 ticks gets within ~0.0002, so the tolerance below is checking real convergence, not
        // an artifact of an undersized loop.
        for (int tick = 0; tick < 400; tick++) {
            velocity = (velocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
        }
        assertEquals(-3.92, velocity, 0.05);
    }

    @Test
    void ceilingsAreOrderedTheWayRealMovementSpeedsAre() {
        // Sanity check on the padded ceilings themselves, not a physics claim - sprint must always
        // be allowed at least as much as walk, and ice (near-zero friction) must allow more than
        // both, or every other task's tests would be building on backwards constants.
        assertTrue(VanillaPhysics.MAX_SPRINT_SPEED_PER_TICK >= VanillaPhysics.MAX_WALK_SPEED_PER_TICK);
        assertTrue(VanillaPhysics.MAX_ICE_SPEED_PER_TICK > VanillaPhysics.MAX_SPRINT_SPEED_PER_TICK);
    }

    @Test
    void jumpVelocityMatchesTheWellKnownOneAndAQuarterBlockJumpHeight() {
        // A vanilla jump with no Jump Boost reaches ~1.25 blocks of height - another widely-cited,
        // independently verifiable reference point.
        double velocity = VanillaPhysics.BASE_JUMP_VELOCITY;
        double height = 0.0;
        double peak = 0.0;
        while (velocity > 0) {
            height += velocity;
            peak = Math.max(peak, height);
            velocity = (velocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
        }
        assertEquals(1.25, peak, 0.1);
    }
}
