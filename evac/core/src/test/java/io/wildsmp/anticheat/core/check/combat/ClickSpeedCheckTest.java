package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickSpeedCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        ClickSpeedCheck check = new ClickSpeedCheck();

        assertEquals("ClickSpeedA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void sustainedInhumanClickRateFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ClickSpeedCheck check = new ClickSpeedCheck();
        registry.register(check);

        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        // 10 attacks in 5 ticks (0.25s) -> 9/0.25 = 36 CPS sustained, well beyond any human rate.
        for (int i = 0; i < 10; i++) {
            long tick = i / 2; // two attacks per tick for a couple of ticks, still well within 5 ticks total
            check.onPacketReceive(data, new AttackPacket(UUID.randomUUID(), tick));
        }

        assertTrue(violationManager.vl(data.id(), "ClickSpeedA") > 0.0);
    }

    @Test
    void normalClickRateNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ClickSpeedCheck check = new ClickSpeedCheck();
        registry.register(check);

        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        // 10 attacks spread across 100 ticks (5s) -> < 2 CPS, an ordinary combat pace.
        for (int i = 0; i < 10; i++) {
            check.onPacketReceive(data, new AttackPacket(UUID.randomUUID(), i * 10L));
        }

        assertEquals(0.0, violationManager.vl(data.id(), "ClickSpeedA"), 0.001);
    }
}
