package io.wildsmp.anticheat.core.violation;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.player.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationManagerTest {

    private static final class TestCheck extends Check {
        TestCheck(String name) {
            super(name, CheckCategory.COMBAT);
        }

        void doFlag(PlayerData data, double vl, String details) {
            flag(data, vl, details);
        }
    }

    @Test
    void addingViolationsBelowThresholdDoesNotFireAListener() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 0.0));
        ViolationManager manager = new ViolationManager(config);
        List<Violation> fired = new ArrayList<>();
        manager.addListener(fired::add);
        TestCheck check = new TestCheck("KillauraA");
        manager.bindForTest(check);
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        check.doFlag(data, 4.0, "small flag");

        assertTrue(fired.isEmpty());
        assertEquals(4.0, manager.vl(data.id(), "KillauraA"), 0.001);
    }

    @Test
    void crossingTheThresholdFiresTheListenerWithACompleteViolation() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 0.0));
        ViolationManager manager = new ViolationManager(config);
        List<Violation> fired = new ArrayList<>();
        manager.addListener(fired::add);
        TestCheck check = new TestCheck("KillauraA");
        manager.bindForTest(check);
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        check.doFlag(data, 6.0, "first");
        check.doFlag(data, 6.0, "second - crosses threshold");

        assertEquals(1, fired.size());
        Violation v = fired.get(0);
        assertEquals(data.id(), v.playerId());
        assertEquals("Steve", v.playerName());
        assertEquals("KillauraA", v.checkName());
        assertEquals(CheckCategory.COMBAT, v.category());
        assertEquals(12.0, v.vl(), 0.001);
        assertEquals(10.0, v.threshold(), 0.001);
        assertEquals("second - crosses threshold", v.details());
    }

    @Test
    void decayReducesVlTowardZeroButNeverBelowIt() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 100.0, 3.0));
        ViolationManager manager = new ViolationManager(config);
        TestCheck check = new TestCheck("SpeedA");
        manager.bindForTest(check);
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        check.doFlag(data, 5.0, "flag");

        manager.decayAll();
        assertEquals(2.0, manager.vl(data.id(), "SpeedA"), 0.001);

        manager.decayAll();
        manager.decayAll();
        assertEquals(0.0, manager.vl(data.id(), "SpeedA"), 0.001);
    }

    @Test
    void clearPlayerRemovesAllTrackedVlForThatPlayer() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 100.0, 0.0));
        ViolationManager manager = new ViolationManager(config);
        TestCheck check = new TestCheck("SpeedA");
        manager.bindForTest(check);
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        check.doFlag(data, 5.0, "flag");

        manager.clearPlayer(data.id());

        assertEquals(0.0, manager.vl(data.id(), "SpeedA"), 0.001);
    }
}
