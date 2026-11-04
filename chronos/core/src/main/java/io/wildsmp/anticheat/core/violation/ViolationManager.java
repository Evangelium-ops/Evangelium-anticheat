package io.wildsmp.anticheat.core.violation;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks violation level (VL) per player per check, applies decay over time, and fires a
 * {@link Violation} to every registered listener whenever a check's VL is at or above its
 * configured threshold. Level-triggered (fires every flag while at/above threshold), not
 * edge-triggered - repeated escalation/cooldown decisions belong to the external listener (the
 * separately-built ban system), keeping this framework's own logic simple per the spec's
 * decoupling goal.
 */
public final class ViolationManager {

    public interface ViolationListener {
        void onViolation(Violation violation);
    }

    private final Map<UUID, Map<String, Double>> vlByPlayerAndCheck = new ConcurrentHashMap<>();
    private final AnticheatConfig config;
    private final List<ViolationListener> listeners = new CopyOnWriteArrayList<>();

    public ViolationManager(AnticheatConfig config) {
        this.config = config;
    }

    public void addListener(ViolationListener listener) {
        listeners.add(listener);
    }

    public void addViolation(PlayerData data, Check check, double vlIncrement, String details) {
        if (vlIncrement <= 0) {
            return;
        }
        Map<String, Double> perCheck = vlByPlayerAndCheck.computeIfAbsent(data.id(), k -> new ConcurrentHashMap<>());
        double newVl = perCheck.merge(check.name(), vlIncrement, Double::sum);
        double threshold = config.threshold(check.name());

        if (newVl >= threshold) {
            Violation violation = new Violation(
                    data.id(), data.name(), check.name(), check.category(), newVl, threshold, details,
                    System.currentTimeMillis());
            for (ViolationListener listener : listeners) {
                listener.onViolation(violation);
            }
        }
    }

    public double vl(UUID playerId, String checkName) {
        return vlByPlayerAndCheck.getOrDefault(playerId, Map.of()).getOrDefault(checkName, 0.0);
    }

    /** Called on a repeating timer by the platform adapter; decays every tracked VL toward zero. */
    public void decayAll() {
        for (Map<String, Double> perCheck : vlByPlayerAndCheck.values()) {
            for (String checkName : List.copyOf(perCheck.keySet())) {
                double decayRate = config.decayRate(checkName);
                if (decayRate <= 0) {
                    continue;
                }
                perCheck.computeIfPresent(checkName, (k, v) -> Math.max(0.0, v - decayRate));
            }
        }
    }

    public void clearPlayer(UUID playerId) {
        vlByPlayerAndCheck.remove(playerId);
    }

    /** Test-only: binds a check to this manager without going through a full CheckRegistry. */
    void bindForTest(Check check) {
        check.bindViolationManager(this);
    }
}
