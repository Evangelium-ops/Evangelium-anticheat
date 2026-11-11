package io.wildsmp.anticheat.bukkit.listener;

import io.wildsmp.anticheat.bukkit.MovementGrace;
import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

/**
 * Makes movement checks compatible with OTHER plugins that legitimately move players - gem/ability
 * plugins (BlissGems launches, dashes, teleports, double jumps) and anything else that calls
 * {@code setVelocity}/{@code teleport} - without depending on any specific plugin's internals.
 * Every such movement is a server-side event the client had no say in, so each one marks the same
 * {@code launchedAndAirborne} flag {@link LaunchImpulseListener} uses for wind charges and
 * explosions; the movement checks already exempt that flag.
 *
 * <p>Combat knockback is deliberately NOT exempted: a velocity change right after damage is
 * VelocityCheck's territory, and exempting it would let a player dodge Fly/Timer just by being hit
 * (see {@link MovementGrace#isCombatKnockback}). Teleports issued by this anticheat's own
 * fly-correction are skipped too, otherwise a flagged flyer would earn an exemption from the very
 * correction that is pulling them down.
 */
public final class PluginMovementListener implements Listener {

    private final PlayerDataManager playerDataManager;
    private final TickClock tickClock;

    public PluginMovementListener(PlayerDataManager playerDataManager, TickClock tickClock) {
        this.playerDataManager = playerDataManager;
        this.tickClock = tickClock;
    }

    /**
     * Only damage dealt by another PLAYER (melee or their arrows/tridents) counts as combat knockback - that is
     * VelocityCheck's territory, and exempting it would let a hacker dodge Fly/Timer by being hit. Hits from mobs
     * (iron golem tosses, ravagers, zombies, skeleton arrows), fire, cacti and the like are server-driven
     * knockback the other player had no say in, so they are NOT recorded here and the velocity that follows is
     * treated as a launch, exactly like a wind charge.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && causedByPlayer(byEntity)) {
            playerDataManager.lastKnownPose(player.getUniqueId()).lastDamageTick = tickClock.current();
        }
    }

    private static boolean causedByPlayer(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            return true;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player;
    }

    /** A riptide trident launches the player (and spins them) with no velocity event, so mark it directly. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(event.getPlayer().getUniqueId());
        markLaunched(pose, tickClock.current());
        pose.fallImpulseExempt = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(event.getPlayer().getUniqueId());
        long now = tickClock.current();
        if (MovementGrace.isCombatKnockback(now, pose.lastDamageTick)) {
            return;
        }
        markLaunched(pose, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (playerDataManager.flyCorrection(player.getUniqueId()).active) {
            return;
        }
        PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(player.getUniqueId());
        long now = tickClock.current();
        markLaunched(pose, now);
        resetFallTracking(pose, event.getTo().getY(), now);
    }

    /**
     * Spectator/creative-style abilities (BlissGems' Astra projection) swap the player's mode and
     * move them freely; the way back is not a fall, so the airborne peak must restart from here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        resetFallTracking(playerDataManager.lastKnownPose(player.getUniqueId()),
                player.getLocation().getY(), tickClock.current());
    }

    /**
     * A server-driven relocation is never a fall: restart the NoFall peak at the new height, drop
     * any fall check already pending against the old position, and pin the peak for a few ticks so
     * movement packets still in flight with the old Y can't raise it again.
     */
    private static void resetFallTracking(PlayerDataManager.LastKnownPose pose, double y, long now) {
        pose.peakYSinceGrounded = y;
        pose.peakResetUntilTick = now + MovementGrace.NOFALL_RESET_WINDOW_TICKS;
        pose.fallCheckPending = false;
        pose.airborneExempt = false;
        pose.airborneStartTick = now;
    }

    private static void markLaunched(PlayerDataManager.LastKnownPose pose, long now) {
        pose.launchedAndAirborne = true;
        pose.lastLaunchTick = now;
    }
}
