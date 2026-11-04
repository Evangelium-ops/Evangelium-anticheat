package io.wildsmp.anticheat.bukkit.listener;

import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import io.wildsmp.anticheat.core.check.combat.MaceAnalyzer;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Clamps Mace hits to what the attacker's own tracked fall distance could legitimately justify,
 * and forwards the server's real (pre-clamp) damage output to the backend so the exploit still
 * gets flagged - see {@link io.wildsmp.anticheat.core.check.combat.MaceDamageCheck} for why this
 * check needs a genuine Bukkit event rather than anything client-reported (a hacked client can't
 * lie about damage the server itself computed and applied). Only Mace hits are handled, not every
 * hit landed - that's the only weapon this check cares about.
 *
 * <p>Runs at {@code HIGHEST} (not {@code MONITOR}, which by convention shouldn't mutate the event)
 * so the clamp is the final word on this hit's damage, applied before the event resolves - unlike
 * every other check in this package, this one prevents the exploit's effect in real time rather
 * than only detecting and flagging it after the fact. That needs a LOCAL, synchronous fall-distance
 * reading ({@link PlayerDataManager.LastKnownPose#peakYSinceGrounded}, maintained by {@link
 * io.wildsmp.anticheat.bukkit.packet.AnticheatPacketListener}) since there's no time to wait on a
 * round trip to the backend's own (more thorough) movement-history tracking inside a synchronous
 * Bukkit event handler. Both this and {@link io.wildsmp.anticheat.core.check.combat.MaceAnalyzer}
 * (backend-side) compute the same ceiling via {@link MaceAnalyzer#maxPlausibleDamage} so the two
 * can't silently drift apart.
 */
public final class MaceDamageListener implements Listener {

    private final BackendClient backendClient;
    private final TickClock tickClock;
    private final PlayerDataManager playerDataManager;
    private final Logger logger;

    public MaceDamageListener(BackendClient backendClient, TickClock tickClock, PlayerDataManager playerDataManager, Logger logger) {
        this.backendClient = backendClient;
        this.tickClock = tickClock;
        this.playerDataManager = playerDataManager;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        if (io.wildsmp.anticheat.bukkit.CombatExemptions.isExempt(attacker.getUniqueId()) || !backendClient.isApproved()) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (weapon.getType() != Material.MACE) {
            return;
        }

        double realDamage = event.getFinalDamage();
        double rawBaseDamage = event.getDamage();

        PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(attacker.getUniqueId());
        double fallDistance = pose.peakYSinceGrounded - pose.y;
        if (fallDistance > 1.5) {
            // a smash attack: vanilla resets the fall and ignores the fall damage of the landing that follows
            pose.fallImpulseExempt = true;
        }
        double maxPlausibleDamage = MaceAnalyzer.maxPlausibleDamage(fallDistance);
        boolean clamped = realDamage > maxPlausibleDamage;
        if (clamped) {
            event.setDamage(maxPlausibleDamage);
        }
        logger.log(Level.INFO, "Mace clamp: attacker {0}, pose.y {1}, peakYSinceGrounded {2}, fallDistance {3}, "
                        + "rawBaseDamage {4}, finalDamage(pre-clamp) {5}, maxPlausible {6}, clamped {7}, "
                        + "finalDamage(post-setDamage) {8}",
                new Object[] {attacker.getName(), pose.y, pose.peakYSinceGrounded, fallDistance,
                        rawBaseDamage, realDamage, maxPlausibleDamage, clamped, event.getFinalDamage()});

        // Always forwards the REAL (pre-clamp) damage the server actually computed - the clamp
        // above only changes what the victim feels in-game; MaceDamageCheck still needs the
        // original number to flag the attempt at all.
        backendClient.sendDamage(attacker.getUniqueId(), event.getEntity().getUniqueId(), realDamage, tickClock.current());
    }
}
