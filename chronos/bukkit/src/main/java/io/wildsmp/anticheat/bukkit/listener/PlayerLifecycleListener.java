package io.wildsmp.anticheat.bukkit.listener;

import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.bukkit.alerts.AlertStreamManager;
import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class PlayerLifecycleListener implements Listener {

    private final PlayerDataManager playerDataManager;
    private final BackendClient backendClient;
    private final AlertStreamManager alertStreamManager;

    public PlayerLifecycleListener(PlayerDataManager playerDataManager, BackendClient backendClient,
                                    AlertStreamManager alertStreamManager) {
        this.playerDataManager = playerDataManager;
        this.backendClient = backendClient;
        this.alertStreamManager = alertStreamManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerDataManager.initializeOffhand(player.getUniqueId(), player.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING);
        backendClient.sendJoin(player.getUniqueId(), player.getName());
        // Safe to call getEntityId() here - PlayerJoinEvent fires on the main thread. This is what
        // lets AnticheatPacketListener resolve an attack's target off-thread without touching live
        // Bukkit world/entity state from PacketEvents' Netty I/O thread.
        playerDataManager.trackEntityId(player.getEntityId(), player.getUniqueId());
        // Ops (or anyone else granted the permission) see anticheat flags by default -
        // /anticheat alerts still works as a per-player mute/unmute toggle on top of this.
        if (player.hasPermission("anticheat.command.alerts")) {
            alertStreamManager.subscribe(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // A death+respawn gives the player a brand-new Bukkit entity ID even though their UUID is
        // unchanged - without re-tracking it here, AnticheatPacketListener's entityId->UUID lookup
        // for any attack against them post-respawn keeps resolving to their now-stale OLD mapping
        // (or, if that old ID got reused by another entity in the meantime, silently to the WRONG
        // player entirely) instead of ever being updated to point at them again.
        Player player = event.getPlayer();
        playerDataManager.trackEntityId(player.getEntityId(), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        backendClient.sendQuit(event.getPlayer().getUniqueId());
        playerDataManager.remove(event.getPlayer().getUniqueId());
        alertStreamManager.unsubscribe(event.getPlayer().getUniqueId());
    }
}
