package io.wildsmp.anticheat.core.check;

import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckRegistryTest {

    private static final class RecordingCheck extends Check {
        RecordingCheck(String name) {
            super(name, CheckCategory.COMBAT);
        }
    }

    @Test
    void checksAreReturnedInDeterministicRegistrationOrder() {
        ViolationManager violationManager = new ViolationManager(AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0)));
        CheckRegistry registry = new CheckRegistry(violationManager);

        Check first = new RecordingCheck("A");
        Check second = new RecordingCheck("B");
        Check third = new RecordingCheck("C");
        registry.register(first);
        registry.register(second);
        registry.register(third);

        List<Check> checks = registry.checks();

        assertEquals(List.of("A", "B", "C"), checks.stream().map(Check::name).toList());
    }

    @Test
    void unregisterRemovesTheCheck() {
        ViolationManager violationManager = new ViolationManager(AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0)));
        CheckRegistry registry = new CheckRegistry(violationManager);
        Check check = new RecordingCheck("A");
        registry.register(check);

        registry.unregister(check);

        assertTrue(registry.checks().isEmpty());
    }

    @Test
    void byNameFindsARegisteredCheck() {
        ViolationManager violationManager = new ViolationManager(AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0)));
        CheckRegistry registry = new CheckRegistry(violationManager);
        Check check = new RecordingCheck("KillauraA");
        registry.register(check);

        assertTrue(registry.byName("KillauraA").isPresent());
        assertTrue(registry.byName("nonexistent").isEmpty());
    }

    @Test
    void flagOnARegisteredCheckRoutesIntoTheBoundViolationManager() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 5.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        Check check = new RecordingCheck("KillauraA");
        registry.register(check);
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        check.testFlag(data, 6.0, "test detail");

        assertEquals(6.0, violationManager.vl(data.id(), "KillauraA"), 0.001);
    }
}
