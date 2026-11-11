package io.wildsmp.anticheat.bukkit.listener;

import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Passively records the tick vanilla's OWN fall-damage system actually engaged for a player -
 * regardless of the resulting amount. {@code AnticheatBukkitPlugin#updateNoFallTracking} correlates
 * this against its own independent, unspoofable real-block landing detection: a confirmed real
 * landing far enough to owe damage with NO matching event at all is the actual NoFall evidence, not
 * a specific missing damage amount (comparing amounts kept producing false positives against every
 * legitimate way vanilla can reduce a hit - Protection, Resistance, Absorption, natural regen
 * landing in the same check window).
 */
public final class FallDamageEventListener implements Listener {

    private final PlayerDataManager playerDataManager;
    private final TickClock tickClock;

    public FallDamageEventListener(PlayerDataManager playerDataManager, TickClock tickClock) {
        this.playerDataManager = playerDataManager;
        this.tickClock = tickClock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        playerDataManager.lastKnownPose(player.getUniqueId()).lastFallDamageEventTick = tickClock.current();
    }
}
