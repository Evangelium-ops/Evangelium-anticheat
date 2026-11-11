package io.wildsmp.anticheat.core.check;

import io.wildsmp.anticheat.core.violation.ViolationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds every registered {@link Check} in deterministic (insertion) order - the order
 * {@link io.wildsmp.anticheat.core.packet.PacketEventBus} dispatches packets/ticks to them.
 */
public final class CheckRegistry {

    private final List<Check> checks = new CopyOnWriteArrayList<>();
    private final ViolationManager violationManager;

    public CheckRegistry(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    public void register(Check check) {
        check.bindViolationManager(violationManager);
        checks.add(check);
    }

    public void unregister(Check check) {
        checks.remove(check);
    }

    public List<Check> checks() {
        return List.copyOf(checks);
    }

    public Optional<Check> byName(String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst();
    }
}
