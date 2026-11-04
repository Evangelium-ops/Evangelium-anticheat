package io.wildsmp.anticheat.bukkit.listener;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Records that a player was just given a real, server-driven velocity impulse they didn't control
 * the timing of - a wind charge, an explosion (TNT, creeper, bed/respawn anchor), or a generic
 * engine push - so movement checks (FlyA, TimerA) can exempt the resulting burst of displacement
 * for the whole flight/fall arc it produces, instead of misreading it as a fly/timer hack.
 *
 * <p>Deliberately excludes combat-driven causes ({@code DAMAGE}, {@code ENTITY_ATTACK}, {@code
 * SWEEP_ATTACK}, {@code SHIELD_BLOCK}) - those are already {@code VelocityCheck}'s territory
 * (core/check/combat), and exempting movement checks for them too would let a player dodge Fly/Timer
 * just by taking a hit. There is no WIND_CHARGE-specific {@code Cause} in this Paper API version -
 * a wind charge's knockback is applied through the same code path as {@code EXPLOSION}, which is
 * the closest available match; unverified against real wind-charge playtest data yet.
 */
public final class LaunchImpulseListener implements Listener {

    private final PlayerDataManager playerDataManager;

    public LaunchImpulseListener(PlayerDataManager playerDataManager) {
        this.playerDataManager = playerDataManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityKnockback(EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event.getCause() != EntityKnockbackEvent.Cause.EXPLOSION
                && event.getCause() != EntityKnockbackEvent.Cause.PUSH) {
            return;
        }
        PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(player.getUniqueId());
        pose.launchedAndAirborne = true;
        if (event.getCause() == EntityKnockbackEvent.Cause.EXPLOSION) {
            // wind charges arrive as EXPLOSION knockback; vanilla ignores the fall damage of that launch
            pose.fallImpulseExempt = true;
        }
    }
}
