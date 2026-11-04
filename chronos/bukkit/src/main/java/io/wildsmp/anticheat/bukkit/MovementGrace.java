package io.wildsmp.anticheat.bukkit;

/**
 * Pure tick-window decisions behind the plugin-compatibility exemptions (other plugins' abilities
 * that launch, teleport or let players fly - e.g. BlissGems), kept free of Bukkit types so the
 * boundaries can be unit tested. All arguments are server ticks from {@code TickClock}; the
 * "never happened" sentinel is {@link Long#MIN_VALUE}, which is why every comparison adds to the
 * stored tick instead of subtracting from the current one (subtracting would overflow).
 */
public final class MovementGrace {

    /**
     * A velocity change within this many ticks of damage is combat knockback, which is VelocityCheck's
     * territory - exempting movement checks for it would let a player dodge Fly/Timer just by being hit.
     */
    public static final int KNOCKBACK_WINDOW_TICKS = 2;

    /**
     * The launch flag survives at least this long even if the player still reads as grounded: the
     * client only leaves the ground a tick or two after the server's velocity packet, and clearing on
     * that first grounded reading would end the exemption before the arc even starts.
     */
    public static final int LAUNCH_GRACE_TICKS = 10;

    /** Flight stays "allowed" this long after the server last granted it (double jump, ability end). */
    public static final int FLIGHT_GRACE_TICKS = 20;

    /**
     * After a server teleport or game-mode change, movement packets still in flight carry the OLD
     * position for a few ticks, so the NoFall peak height is pinned to the current Y for this long
     * instead of accumulating a maximum - otherwise a stale high packet would re-raise it and the
     * teleport would read as a long fall.
     */
    public static final int NOFALL_RESET_WINDOW_TICKS = 10;

    /**
     * After the last tick a player was in water or lava they still count as in it for this many ticks: bobbing at the
     * surface, hopping out of the water and the little boost vanilla gives when you swim into a block at the shore all
     * leave the water for a tick or two, and without this the swimmer reads as flying or climbing a wall.
     */
    public static final int WATER_GRACE_TICKS = 10;

    /**
     * Climbing off the top of a ladder or vines leaves the climbable block for a tick or two while the climb's upward
     * motion carries on, so a few ticks after the last climbing tick still count as climbing.
     */
    public static final int CLIMB_GRACE_TICKS = 5;

    private MovementGrace() {
    }

    public static boolean climbGraceActive(long now, long lastClimbTick) {
        return now <= lastClimbTick + CLIMB_GRACE_TICKS;
    }

    public static boolean waterGraceActive(long now, long lastWaterTick) {
        return now <= lastWaterTick + WATER_GRACE_TICKS;
    }

    public static boolean isCombatKnockback(long now, long lastDamageTick) {
        return now <= lastDamageTick + KNOCKBACK_WINDOW_TICKS;
    }

    public static boolean launchGraceActive(long now, long lastLaunchTick) {
        return now <= lastLaunchTick + LAUNCH_GRACE_TICKS;
    }

    public static boolean peakResetActive(long now, long resetUntilTick) {
        return now <= resetUntilTick;
    }

    public static boolean flightStillAllowed(long now, long lastAllowFlightTick) {
        return now <= lastAllowFlightTick + FLIGHT_GRACE_TICKS;
    }
}
