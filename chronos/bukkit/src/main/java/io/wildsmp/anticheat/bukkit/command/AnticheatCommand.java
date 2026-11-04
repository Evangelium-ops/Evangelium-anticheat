package io.wildsmp.anticheat.bukkit.command;

import io.wildsmp.anticheat.bukkit.AnticheatBukkitPlugin;
import io.wildsmp.anticheat.bukkit.alerts.AlertStreamManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class AnticheatCommand implements CommandExecutor {

    private final AnticheatBukkitPlugin plugin;
    private final AlertStreamManager alertStreamManager;

    public AnticheatCommand(AnticheatBukkitPlugin plugin, AlertStreamManager alertStreamManager) {
        this.plugin = plugin;
        this.alertStreamManager = alertStreamManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "Usage: /anticheat <reload|alerts|status|debug>");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "alerts" -> handleAlerts(sender);
            case "status" -> handleStatus(sender);
            case "debug" -> handleDebug(sender);
            default -> {
                sender.sendMessage(ChatColor.AQUA + "Usage: /anticheat <reload|alerts|status|debug>");
                yield true;
            }
        };
    }

    /** What AC combat sees where you stand right now (block under you, climbing/water state, the terrain it reports). */
    private boolean handleDebug(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this.");
            return true;
        }
        if (!sender.hasPermission("anticheat.command.status")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        sender.sendMessage(ChatColor.AQUA + "AC combat movement state:");
        for (String line : plugin.describeMovementState(player)) {
            sender.sendMessage(ChatColor.GRAY + " " + line);
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("anticheat.command.reload")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        plugin.reloadFromDisk();
        sender.sendMessage(ChatColor.GREEN + "AC combat config reloaded.");
        return true;
    }

    private boolean handleAlerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can toggle alerts.");
            return true;
        }
        if (!sender.hasPermission("anticheat.command.alerts")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        boolean nowOn = alertStreamManager.toggle(player.getUniqueId());
        sender.sendMessage(nowOn
                ? ChatColor.GREEN + "AC combat alerts: ON"
                : ChatColor.YELLOW + "AC combat alerts: OFF");
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("anticheat.command.status")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }
        var status = plugin.connectionState().status();
        ChatColor color = switch (status) {
            case CONNECTED -> ChatColor.GREEN;
            case GRACE_PERIOD -> ChatColor.YELLOW;
            case FAILED_CLOSED -> ChatColor.RED;
        };
        sender.sendMessage(ChatColor.AQUA + "AC combat backend: " + color + status);
        sender.sendMessage(ChatColor.AQUA + "Authorization: " + ChatColor.WHITE + "Evangelium launcher license");
        return true;
    }
}
