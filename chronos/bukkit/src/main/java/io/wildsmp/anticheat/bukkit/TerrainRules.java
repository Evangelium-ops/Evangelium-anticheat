package io.wildsmp.anticheat.bukkit;

/**
 * Pure terrain-classification rules, kept free of Bukkit types so they can be unit tested.
 */
public final class TerrainRules {

    private TerrainRules() {
    }

    /**
     * Whether the player should be treated as being IN liquid (swimming/floating terrain, which is
     * what JesusA judges) rather than standing on the ground in it. Liquid at the feet with a real
     * solid, non-liquid block directly below is just wading: shallow water, a partial/flowing water
     * block, a puddle over a floor - vanilla lets a player walk through all of that normally, and
     * treating it as liquid terrain made walking into the edge of a half-height water block read as
     * "walking on water".
     *
     * <p>Standing on a liquid SURFACE is only possible over a liquid at least two blocks deep, so only liquid with more
     * liquid directly below counts. Water that spreads out thinly after a bucket is placed, water pouring off a ledge over
     * air, and any other single flowing layer are never "liquid terrain" - treating them as such made players walking
     * into spreading water read as Jesus hackers.
     */
    public static boolean standingInLiquid(boolean feetBlockIsLiquid, boolean blockBelowIsSolid,
                                           boolean blockBelowIsLiquid) {
        return feetBlockIsLiquid && blockBelowIsLiquid;
    }
}
