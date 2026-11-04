package io.wildsmp.anticheat.core.check.movement;

/**
 * The well-documented, independently-verifiable vanilla movement physics constants every check in
 * this package builds its ceiling on - real values first (verified against the well-known
 * reference points {@link VanillaPhysicsTest} checks: ~-3.92 blocks/tick terminal fall velocity,
 * ~1.25 block jump height), THEN generous padding on top, matching every prior physics-based check
 * in this project (ReachCheck.EPSILON, MaceAnalyzer.MAX_DAMAGE_PER_FALL_BLOCK,
 * VelocityAnalyzer's peak-rise ceiling) - never a knife-edge threshold.
 *
 * <p>Deliberately NOT a full continuous-acceleration simulation of vanilla's actual per-tick input
 * handling (which ramps up toward a target speed rather than moving at a constant rate) - every
 * ceiling here is the real, documented MAXIMUM a player can reach, padded further, so a legitimate
 * player's ramp-up/direction-change/partial-key-press variance always stays under it. This mirrors
 * the whole project's established "generous ceiling beats precise simulation" approach.
 */
public final class VanillaPhysics {

    private VanillaPhysics() {
    }

    // --- Gravity/drag (airborne, no liquid/climbable) ---
    public static final double GRAVITY_PER_TICK = 0.08;
    public static final double AIR_DRAG = 0.98;

    // --- Jumping ---
    public static final double BASE_JUMP_VELOCITY = 0.42;
    // Each Jump Boost level adds roughly this much extra initial vertical velocity - approximate,
    // padded generously since exact enchant/effect stacking isn't modeled here.
    public static final double JUMP_VELOCITY_PER_LEVEL_BONUS = 0.15;

    // --- Horizontal speed ceilings, per tick, already padded above the real documented
    // steady-state maximum (walk ~0.2158, sprint ~0.2806 blocks/tick) ---
    public static final double MAX_WALK_SPEED_PER_TICK = 0.32;
    public static final double MAX_SPRINT_SPEED_PER_TICK = 0.42;
    // Near-frictionless ice/packed ice/blue ice lets sprint momentum persist far longer than
    // normal ground - real observed max is roughly 9-10 blocks/second; padded well above that.
    public static final double MAX_ICE_SPEED_PER_TICK = 0.65;
    // How much a Speed/Slowness potion level scales the base horizontal ceiling, multiplicatively,
    // per level - vanilla's real effect is roughly +/-20% per level; padded to 25%.
    public static final double POTION_SPEED_MULTIPLIER_PER_LEVEL = 0.25;

    // --- Liquid surface (Jesus check cares about horizontal movement ON a liquid, not swim
    // speed specifically) ---
    public static final double MAX_LIQUID_SURFACE_SPEED_PER_TICK = 0.3;

    // --- Climbing (ladder/vine/scaffolding) ---
    public static final double MAX_CLIMB_SPEED_PER_TICK = 0.25;

    // --- Fall damage ---
    // Vanilla only applies fall damage beyond 3 blocks of fall distance - padded slightly for
    // tick-snap tolerance, matching every other "how far did they really fall" check in this
    // project (see MaceAnalyzer's own fall-distance tracking).
    public static final double FALL_DAMAGE_SAFE_DISTANCE_BLOCKS = 3.5;
}
