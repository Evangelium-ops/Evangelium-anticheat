package io.wildsmp.anticheat.bukkit.scheduling;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Runs a repeating task using Folia's global region scheduler when present, falling back to the
 * plain Bukkit scheduler on Paper/Spigot - Folia has no single "main thread" the legacy scheduler
 * can safely use for a task like VL decay that touches every online player's state regardless of
 * which region they're in.
 */
public final class FoliaSchedulerAdapter {

    private final boolean folia;

    public FoliaSchedulerAdapter() {
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public boolean isFolia() {
        return folia;
    }

    /** Repeats {@code task} every {@code periodTicks}, starting after {@code initialDelayTicks}. */
    public void runRepeating(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, scheduledTask -> task.run(), Math.max(1, initialDelayTicks), Math.max(1, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(1, initialDelayTicks), Math.max(1, periodTicks));
        }
    }

    /** Runs {@code task} once, after {@code delayTicks}. */
    public void runOnce(Plugin plugin, Runnable task, long delayTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), Math.max(1, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, delayTicks));
        }
    }

    /** Runs {@code task} once on the thread that owns {@code player} (their region on Folia), after {@code delayTicks}. */
    public void runOnPlayer(Plugin plugin, org.bukkit.entity.Player player, Runnable task, long delayTicks) {
        if (folia) {
            player.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, Math.max(1, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, delayTicks));
        }
    }
}
