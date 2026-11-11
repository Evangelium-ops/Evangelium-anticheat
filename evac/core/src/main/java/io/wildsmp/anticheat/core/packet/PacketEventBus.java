package io.wildsmp.anticheat.core.packet;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Dispatches incoming packets and server ticks to every registered {@link Check} in
 * deterministic order. Every check invocation is isolated in its own try/catch so one broken
 * check can never crash the host server or block the others - a check that fails five times in a
 * row is automatically disabled and logged clearly rather than allowed to keep destabilizing the
 * server.
 */
public final class PacketEventBus {

    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final CheckRegistry registry;
    private final BiConsumer<String, Throwable> failureLogger;
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Set<String> disabledChecks = ConcurrentHashMap.newKeySet();

    public PacketEventBus(CheckRegistry registry, BiConsumer<String, Throwable> failureLogger) {
        this.registry = registry;
        this.failureLogger = failureLogger;
    }

    public void dispatchPacketReceive(PlayerData data, InboundPacket packet) {
        for (Check check : registry.checks()) {
            if (disabledChecks.contains(check.name())) {
                continue;
            }
            try {
                check.onPacketReceive(data, packet);
                consecutiveFailures.put(check.name(), 0);
            } catch (Throwable t) {
                handleFailure(check, t);
            }
        }
    }

    public void dispatchTick(PlayerData data) {
        for (Check check : registry.checks()) {
            if (disabledChecks.contains(check.name())) {
                continue;
            }
            try {
                check.onTick(data);
                consecutiveFailures.put(check.name(), 0);
            } catch (Throwable t) {
                handleFailure(check, t);
            }
        }
    }

    private void handleFailure(Check check, Throwable t) {
        int failures = consecutiveFailures.merge(check.name(), 1, Integer::sum);
        failureLogger.accept(check.name(), t);
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            disabledChecks.add(check.name());
            failureLogger.accept(check.name(), new IllegalStateException(
                    "Check '" + check.name() + "' disabled after " + MAX_CONSECUTIVE_FAILURES + " consecutive failures"));
        }
    }

    public boolean isDisabled(String checkName) {
        return disabledChecks.contains(checkName);
    }

    public void reenable(String checkName) {
        disabledChecks.remove(checkName);
        consecutiveFailures.put(checkName, 0);
    }

    /** Test-only: force-disables a check without needing 5 real failures first. */
    void disableForTest(String checkName) {
        disabledChecks.add(checkName);
    }
}
