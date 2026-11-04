package io.wildsmp.anticheat.bukkit.listener;

import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records the two genuinely legitimate ways an item can end up in a player's offhand - a real
 * {@link InventoryClickEvent} (which can only fire while their inventory screen is actually open,
 * so it's the only path that can move a totem out of inventory STORAGE) or a real {@link
 * PlayerSwapHandItemsEvent} (the F-key swap between main and offhand, which doesn't touch storage
 * at all and is legitimate no matter how fast) - and cancels a resurrect whose totem was placed
 * through neither. See {@link PlayerDataManager.OffhandTotemState} for why this replaced an
 * earlier, timing-based attempt that didn't hold up live.
 *
 * <p>Only offhand resurrects are covered - a totem consumed from the main hand isn't handled by
 * this check yet.
 */
public final class AutoTotemListener implements Listener {

    private final BackendClient backendClient;
    private final TickClock tickClock;
    private final PlayerDataManager playerDataManager;
    private final Logger logger;

    public AutoTotemListener(BackendClient backendClient, TickClock tickClock, PlayerDataManager playerDataManager, Logger logger) {
        this.backendClient = backendClient;
        this.tickClock = tickClock;
        this.playerDataManager = playerDataManager;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        playerDataManager.offhandTotemState(player.getUniqueId()).lastLegitimateActionTick = tickClock.current();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        playerDataManager.offhandTotemState(event.getPlayer().getUniqueId()).lastLegitimateActionTick = tickClock.current();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (io.wildsmp.anticheat.bukkit.CombatExemptions.isExempt(player.getUniqueId()) || !backendClient.isApproved()) return;
        if (event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }

        // The per-tick tracker (AnticheatBukkitPlugin#updateOffhandTotemTracking) already strips
        // an illegitimately-placed totem the moment it appears, so this is mostly a same-tick
        // safety net for the rare race where a resurrect fires before that poll has run yet.
        PlayerDataManager.OffhandTotemState state = playerDataManager.offhandTotemState(player.getUniqueId());
        boolean legitimate = state.legitimatelyEquipped;
        if (!legitimate) {
            event.setCancelled(true);
        }
        state.equippedSinceTick = Long.MIN_VALUE;

        logger.log(Level.INFO, "AutoTotem: player {0} resurrected, legitimatelyEquipped {1}, cancelled {2}",
                new Object[] {player.getName(), legitimate, !legitimate});
        if (!legitimate) {
            backendClient.sendAutoTotem(player.getUniqueId(), tickClock.current());
        }
    }
}
