package io.wildsmp.anticheat.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainRulesTest {

    @Test
    void wadingInShallowWaterOverSolidGroundIsNotLiquidTerrain() {
        // feet in a (partial) water block, solid non-liquid block underneath
        assertFalse(TerrainRules.standingInLiquid(true, true, false));
    }

    @Test
    void waterWithMoreWaterBelowIsLiquidTerrain() {
        // open water / a deeper pool: the block below is water too
        assertTrue(TerrainRules.standingInLiquid(true, false, true));
    }

    @Test
    void spreadingOrFallingWaterOverAirIsNotLiquidTerrain() {
        // a single flowing layer pouring off a ledge has air below, nothing a surface could rest on
        assertFalse(TerrainRules.standingInLiquid(true, false, false));
    }

    @Test
    void aLiquidBlockBelowNeverCountsAsSupport() {
        // some materials report both solid-ish and liquid; a liquid below is not ground
        assertTrue(TerrainRules.standingInLiquid(true, true, true));
    }

    @Test
    void noLiquidAtTheFeetIsNeverLiquidTerrain() {
        assertFalse(TerrainRules.standingInLiquid(false, false, false));
        assertFalse(TerrainRules.standingInLiquid(false, true, false));
    }
}
