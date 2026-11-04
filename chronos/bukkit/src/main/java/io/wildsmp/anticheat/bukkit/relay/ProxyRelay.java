package io.wildsmp.anticheat.bukkit.relay;

import io.wildsmp.anticheat.core.violation.Violation;
import io.wildsmp.anticheat.core.violation.ViolationCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Forwards a {@link Violation} to the proxy over plugin messaging, matching {@link
 * ViolationCodec}'s wire format on both ends. Separate from the punishment path (see {@link
 * io.wildsmp.anticheat.bukkit.bansystem.BanSystemIntegration}) - this is for network-wide staff
 * alert visibility, not punishment. Plugin-messaging requires an online player to carry the
 * message, so this best-effort-picks whichever player is online; if nobody is online there is
 * nothing to relay to anyway.
 */
public final class ProxyRelay {

    public static final String CHANNEL = "anticheat:violation";

    private final Plugin plugin;

    public ProxyRelay(Plugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    public void relay(Violation violation) {
        Player carrier = Bukkit.getOnlinePlayers().stream().findAny().orElse(null);
        if (carrier == null) {
            return;
        }
        carrier.sendPluginMessage(plugin, CHANNEL, ViolationCodec.encode(violation));
    }
}
