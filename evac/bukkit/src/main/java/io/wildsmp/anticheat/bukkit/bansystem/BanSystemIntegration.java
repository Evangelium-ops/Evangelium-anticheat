package io.wildsmp.anticheat.bukkit.bansystem;

import io.wildsmp.anticheat.bukkit.scheduling.FoliaSchedulerAdapter;
import io.wildsmp.anticheat.core.violation.Violation;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Punishment integration with our existing BanSystem. On a threshold crossing, dispatches the
 * local {@code bsint-anticheat <player> <details>} console command - the same command
 * ChunkTown / BanSystem-Paper / BanSystem-Folia already register, which forwards to the proxy's
 * real {@code /anticheat} command and its 3-warnings-then-ban escalation (the same pipeline
 * Vulcan's punishment-commands already use today). This anticheat never talks to BanSystem
 * directly - it only reuses the exact command-dispatch surface that's already deployed and
 * already proven, so nothing on the BanSystem side needs to change for this to work.
 *
 * <p>Requires one of those plugins to also be installed on this server; if none is, {@code
 * Bukkit.dispatchCommand} just logs "Unknown command" with no other effect - this integration
 * does not itself verify the command exists.
 */
public final class BanSystemIntegration {

    private final Plugin plugin;
    private final FoliaSchedulerAdapter scheduler;
    private final String command;

    public BanSystemIntegration(Plugin plugin, FoliaSchedulerAdapter scheduler, String command) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.command = command;
    }

    public void onViolation(Violation violation) {
        String fullCommand = command + " " + violation.playerName()
                + (violation.details() == null || violation.details().isBlank() ? "" : " " + violation.details());
        // Command dispatch touches world/player state, so it must run on a safe scheduler thread
        // rather than whatever thread the violation was flagged from (a PacketEvents callback,
        // not necessarily the main/region thread).
        scheduler.runOnce(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fullCommand), 1L);
    }
}
