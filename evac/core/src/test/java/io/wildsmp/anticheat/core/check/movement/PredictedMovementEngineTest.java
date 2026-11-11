package io.wildsmp.anticheat.core.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictedMovementEngineTest {

    private final PredictedMovementEngine engine = new PredictedMovementEngine();

    @Test
    void normalGroundedWalkingUsesTheWalkCeiling() {
        double ceiling = engine.maxHorizontalDisplacement(TerrainKind.NORMAL, MovementEffects.none());

        assertEquals(VanillaPhysics.MAX_WALK_SPEED_PER_TICK, ceiling, 0.001);
    }

    @Test
    void sprintingUsesTheHigherSprintCeiling() {
        MovementEffects sprinting = new MovementEffects(0, 0, 0, false, false, true, false, false, false, false, false);

        double ceiling = engine.maxHorizontalDisplacement(TerrainKind.NORMAL, sprinting);

        assertEquals(VanillaPhysics.MAX_SPRINT_SPEED_PER_TICK, ceiling, 0.001);
    }

    @Test
    void iceAllowsMoreThanNormalGroundEvenWhileSprinting() {
        MovementEffects sprinting = new MovementEffects(0, 0, 0, false, false, true, false, false, false, false, false);

        double normalCeiling = engine.maxHorizontalDisplacement(TerrainKind.NORMAL, sprinting);
        double iceCeiling = engine.maxHorizontalDisplacement(TerrainKind.ICE, sprinting);

        assertTrue(iceCeiling > normalCeiling);
    }

    @Test
    void speedPotionScalesTheCeilingUp() {
        MovementEffects speedTwo = new MovementEffects(2, 0, 0, false, false, false, false, false, false, false, false);

        double base = engine.maxHorizontalDisplacement(TerrainKind.NORMAL, MovementEffects.none());
        double boosted = engine.maxHorizontalDisplacement(TerrainKind.NORMAL, speedTwo);

        assertEquals(base * (1.0 + 2 * VanillaPhysics.POTION_SPEED_MULTIPLIER_PER_LEVEL), boosted, 0.001);
    }

    @Test
    void justJumpingWithNoJumpBoostUsesTheBaseJumpVelocity() {
        double rise = engine.maxVerticalRise(MovementEffects.none(), true);

        assertEquals(VanillaPhysics.BASE_JUMP_VELOCITY, rise, 0.001);
    }

    @Test
    void jumpBoostAddsOnTopOfTheBaseJumpVelocity() {
        MovementEffects jumpBoostTwo = new MovementEffects(0, 0, 2, false, false, false, false, false, false, false, false);

        double rise = engine.maxVerticalRise(jumpBoostTwo, true);

        assertEquals(VanillaPhysics.BASE_JUMP_VELOCITY + 2 * VanillaPhysics.JUMP_VELOCITY_PER_LEVEL_BONUS, rise, 0.001);
    }

    @Test
    void descentMagnitudeNeverExceedsTerminalVelocityNoMatterHowLongAirborne() {
        double descentAfterManyTicks = engine.maxVerticalDescent(200);

        // Terminal velocity from gravity/drag reaching equilibrium (see VanillaPhysicsTest) -
        // falling for a long time never legitimately produces a single-tick drop bigger than this.
        assertTrue(descentAfterManyTicks <= 4.0);
    }

    @Test
    void descentMagnitudeGrowsWithTicksAirborneUntilTerminalVelocity() {
        double early = engine.maxVerticalDescent(2);
        double later = engine.maxVerticalDescent(20);

        assertTrue(later > early);
    }
}
