package io.wildsmp.anticheat.core.packet;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketEventBusTest {

    private static final class CountingCheck extends Check {
        int tickCalls = 0;

        CountingCheck(String name) {
            super(name, CheckCategory.MOVEMENT);
        }

        @Override
        public void onTick(PlayerData data) {
            tickCalls++;
        }
    }

    private static final class AlwaysThrowsCheck extends Check {
        AlwaysThrowsCheck(String name) {
            super(name, CheckCategory.COMBAT);
        }

        @Override
        public void onTick(PlayerData data) {
            throw new RuntimeException("simulated check failure");
        }
    }

    private static CheckRegistry newRegistry() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 100.0, 0.0));
        return new CheckRegistry(new ViolationManager(config));
    }

    @Test
    void dispatchTickCallsEveryRegisteredCheck() {
        CheckRegistry registry = newRegistry();
        CountingCheck a = new CountingCheck("A");
        CountingCheck b = new CountingCheck("B");
        registry.register(a);
        registry.register(b);
        List<String> failures = new ArrayList<>();
        PacketEventBus bus = new PacketEventBus(registry, (name, t) -> failures.add(name));
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        bus.dispatchTick(data);

        assertEquals(1, a.tickCalls);
        assertEquals(1, b.tickCalls);
        assertTrue(failures.isEmpty());
    }

    @Test
    void aThrowingCheckDoesNotStopOtherChecksFromRunning() {
        CheckRegistry registry = newRegistry();
        AlwaysThrowsCheck broken = new AlwaysThrowsCheck("Broken");
        CountingCheck healthy = new CountingCheck("Healthy");
        registry.register(broken);
        registry.register(healthy);
        PacketEventBus bus = new PacketEventBus(registry, (name, t) -> {
        });
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        bus.dispatchTick(data);

        assertEquals(1, healthy.tickCalls);
    }

    @Test
    void aCheckIsAutoDisabledAfterFiveConsecutiveFailures() {
        CheckRegistry registry = newRegistry();
        AlwaysThrowsCheck broken = new AlwaysThrowsCheck("Broken");
        registry.register(broken);
        List<String> failures = new ArrayList<>();
        PacketEventBus bus = new PacketEventBus(registry, (name, t) -> failures.add(name));
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        for (int i = 0; i < 5; i++) {
            bus.dispatchTick(data);
        }

        assertTrue(bus.isDisabled("Broken"));
        assertTrue(failures.size() >= 5);
    }

    @Test
    void aDisabledCheckIsSkippedOnFutureDispatches() {
        CheckRegistry registry = newRegistry();
        CountingCheck check = new CountingCheck("A");
        registry.register(check);
        PacketEventBus bus = new PacketEventBus(registry, (name, t) -> {
        });
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        bus.dispatchTick(data);
        assertEquals(1, check.tickCalls);

        bus.disableForTest("A");
        bus.dispatchTick(data);

        assertEquals(1, check.tickCalls, "disabled check must not be invoked again");
    }

    @Test
    void reenableAllowsADisabledCheckToRunAgain() {
        CheckRegistry registry = newRegistry();
        CountingCheck check = new CountingCheck("A");
        registry.register(check);
        PacketEventBus bus = new PacketEventBus(registry, (name, t) -> {
        });
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        bus.disableForTest("A");

        bus.reenable("A");
        bus.dispatchTick(data);

        assertEquals(1, check.tickCalls);
        assertFalse(bus.isDisabled("A"));
    }
}
