package io.wildsmp.anticheat.bukkit.alerts;

import io.wildsmp.anticheat.core.violation.Violation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which staff members have toggled on the live alert stream via {@code /anticheat alerts}. */
public final class AlertStreamManager {

    private final Set<UUID> subscribed = ConcurrentHashMap.newKeySet();

    public boolean toggle(UUID staffId) {
        if (subscribed.remove(staffId)) {
            return false;
        }
        subscribed.add(staffId);
        return true;
    }

    /** Auto-subscribes a player who has the alerts permission - called on join so ops see flags by default. */
    public void subscribe(UUID staffId) {
        subscribed.add(staffId);
    }

    public void unsubscribe(UUID staffId) {
        subscribed.remove(staffId);
    }

    public boolean isSubscribed(UUID staffId) {
        return subscribed.contains(staffId);
    }

    public void broadcast(Violation violation, org.bukkit.plugin.Plugin owner) {
        String message = String.format(
                "§c[AC] §f%s §7failed §f%s §7(%.1f/%.1f) §8| %s",
                violation.playerName(), violation.checkName(), violation.vl(), violation.threshold(),
                violation.details());
        for (UUID staffId : subscribed) {
            Player staff = Bukkit.getPlayer(staffId);
            if (staff != null && staff.isOnline()) {
                staff.getScheduler().run(owner, task -> {
                    if (staff.isOnline() && subscribed.contains(staffId)) staff.sendMessage(message);
                }, null);
            }
        }
    }
}
