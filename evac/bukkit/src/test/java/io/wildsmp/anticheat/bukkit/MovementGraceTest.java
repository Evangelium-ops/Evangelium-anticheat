package io.wildsmp.anticheat.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementGraceTest {

    @Test
    void velocityRightAfterDamageIsCombatKnockback() {
        assertTrue(MovementGrace.isCombatKnockback(100, 100));
        assertTrue(MovementGrace.isCombatKnockback(102, 100));
        assertFalse(MovementGrace.isCombatKnockback(103, 100));
    }

    @Test
    void neverDamagedIsNeverKnockback() {
        assertFalse(MovementGrace.isCombatKnockback(5, Long.MIN_VALUE));
    }

    @Test
    void launchGraceCoversTheWindowThenExpires() {
        assertTrue(MovementGrace.launchGraceActive(100, 100));
        assertTrue(MovementGrace.launchGraceActive(110, 100));
        assertFalse(MovementGrace.launchGraceActive(111, 100));
        assertFalse(MovementGrace.launchGraceActive(5, Long.MIN_VALUE));
    }

    @Test
    void peakResetCoversUntilItsDeadlineThenExpires() {
        assertTrue(MovementGrace.peakResetActive(105, 110));
        assertTrue(MovementGrace.peakResetActive(110, 110));
        assertFalse(MovementGrace.peakResetActive(111, 110));
        assertFalse(MovementGrace.peakResetActive(5, Long.MIN_VALUE));
    }

    @Test
    void flightGraceCoversTheWindowThenExpires() {
        assertTrue(MovementGrace.flightStillAllowed(100, 100));
        assertTrue(MovementGrace.flightStillAllowed(120, 100));
        assertFalse(MovementGrace.flightStillAllowed(121, 100));
        assertFalse(MovementGrace.flightStillAllowed(5, Long.MIN_VALUE));
    }

    @Test
    void aSwimmerCountsAsStillInWaterForTenTicksAfterLeavingIt() {
        assertTrue(MovementGrace.waterGraceActive(100, 100));
        assertTrue(MovementGrace.waterGraceActive(110, 100));
        assertFalse(MovementGrace.waterGraceActive(111, 100));
    }

    @Test
    void neverHavingBeenInWaterNeverGivesTheGrace() {
        assertFalse(MovementGrace.waterGraceActive(100, Long.MIN_VALUE));
    }

    @Test
    void climbingOffTheTopOfVinesKeepsCountingAsClimbingBriefly() {
        assertTrue(MovementGrace.climbGraceActive(100, 100));
        assertTrue(MovementGrace.climbGraceActive(105, 100));
        assertFalse(MovementGrace.climbGraceActive(106, 100));
        assertFalse(MovementGrace.climbGraceActive(100, Long.MIN_VALUE));
    }
}
