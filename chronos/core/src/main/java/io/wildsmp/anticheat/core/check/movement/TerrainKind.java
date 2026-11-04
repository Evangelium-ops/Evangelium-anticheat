package io.wildsmp.anticheat.core.check.movement;

/** The block/liquid/climbable the player's collision box currently intersects - computed bukkit-side, once per movement packet. */
public enum TerrainKind {
    NORMAL, ICE, PACKED_ICE, SLIME, HONEY, SOUL_SAND, LIQUID_WATER, LIQUID_LAVA, CLIMBABLE, AIR,
    /**
     * Body in water or lava but resting on the bottom (so NOT {@link #LIQUID_WATER}/{@link #LIQUID_LAVA}, which mean being
     * held up by the liquid). The client reports onGround=false while wading along a lake bed, which is not flying or
     * wall-climbing. Appended last: terrain is sent over the wire as its ordinal.
     */
    SUBMERGED
}
