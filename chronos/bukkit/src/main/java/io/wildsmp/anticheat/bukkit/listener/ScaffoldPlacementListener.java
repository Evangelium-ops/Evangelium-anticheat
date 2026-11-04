package io.wildsmp.anticheat.bukkit.listener;

import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Forwards the raw fact of every real block placement (who, where, when) - no geometry is computed
 * here. {@code ScaffoldAnalyzer}/{@code ScaffoldCheck} (backend-side) independently re-derive
 * whether the placement was actually within the player's real look direction/reach, using the
 * placer's own last known movement sample - same "detection logic never ships in the bukkit jar"
 * reasoning as every other check.
 */
public final class ScaffoldPlacementListener implements Listener {

    private final BackendClient backendClient;
    private final TickClock tickClock;

    public ScaffoldPlacementListener(BackendClient backendClient, TickClock tickClock) {
        this.backendClient = backendClient;
        this.tickClock = tickClock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        backendClient.sendBlockPlace(event.getPlayer().getUniqueId(),
                block.getX(), block.getY(), block.getZ(), tickClock.current());
    }
}
