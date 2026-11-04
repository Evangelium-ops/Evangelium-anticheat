package io.wildsmp.anticheat.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoFallRulesTest {

    private static final double SAFE = 3.5;

    @Test
    void walkingDownAStaircaseOfFullBlocksNeverAccumulatesAFall() {
        // Reproduces the live false flag: steps 134 -> 133 -> 132 -> 131 -> 130 -> 129 -> 128, each
        // step touching the ground for only a tick, so no debounced landing is ever confirmed.
        double peak = 134.0;
        double y = 134.0;
        for (int step = 0; step < 6; step++) {
            y -= 1.0;
            if (NoFallRules.shouldResetPeakOnGroundContact(peak, y, SAFE)) {
                peak = y;
            }
            assertTrue(peak - y <= SAFE, "peak must not accumulate across steps");
        }
        assertEquals(128.0, y, 0.0001);
        assertEquals(128.0, peak, 0.0001);
    }

    @Test
    void aRealFallBeyondTheSafeDistanceIsNotResetByASingleTickContact() {
        assertFalse(NoFallRules.shouldResetPeakOnGroundContact(140.0, 116.0, SAFE));
        assertFalse(NoFallRules.shouldResetPeakOnGroundContact(110.0, 106.4, SAFE));
    }

    @Test
    void boundaryAtExactlyTheSafeDistanceResets() {
        assertTrue(NoFallRules.shouldResetPeakOnGroundContact(103.5, 100.0, SAFE));
        assertTrue(NoFallRules.shouldResetPeakOnGroundContact(100.0, 100.0, SAFE));
    }

    @Test
    void landingInWaterOrOnSlimeOrClimbablesNeverOwesFallDamage() {
        for (String name : new String[] {"WATER", "LAVA", "SLIME_BLOCK", "HONEY_BLOCK", "COBWEB", "POWDER_SNOW",
                "LADDER", "VINE", "SCAFFOLDING", "RED_BED", "WHITE_BED", "HAY_BLOCK", "BUBBLE_COLUMN"}) {
            assertTrue(NoFallRules.negatesFallDamage(name), name);
        }
    }

    @Test
    void ordinaryBlocksStillOweFallDamage() {
        for (String name : new String[] {"STONE", "DIRT", "GRASS_BLOCK", "OAK_PLANKS", "AIR", "SAND", "ICE"}) {
            assertFalse(NoFallRules.negatesFallDamage(name), name);
        }
    }

    @Test
    void eachJumpBoostLevelAddsABlockOfSafeFall() {
        assertEquals(3.5, NoFallRules.safeDistance(3.5, 0), 0.0001);
        assertEquals(4.5, NoFallRules.safeDistance(3.5, 1), 0.0001);
        assertEquals(6.5, NoFallRules.safeDistance(3.5, 3), 0.0001);
        assertEquals(3.5, NoFallRules.safeDistance(3.5, -2), 0.0001);
    }

    @Test
    void recentDamageFramesSwallowFallDamageWithoutAnEvent() {
        assertTrue(NoFallRules.inDamageImmunityFrames(15, 20));
        assertFalse(NoFallRules.inDamageImmunityFrames(10, 20));
        assertFalse(NoFallRules.inDamageImmunityFrames(0, 20));
    }
}
