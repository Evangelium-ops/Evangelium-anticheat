package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.BlockPlacePacket;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldSpeedCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        ScaffoldSpeedCheck check = new ScaffoldSpeedCheck();

        assertEquals("ScaffoldSpeedA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void sustainedInhumanPlacementRateFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldSpeedCheck check = new ScaffoldSpeedCheck();
        registry.register(check);

        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        // 10 placements in 5 ticks (0.25s) -> 9/0.25 = 36 placements/sec sustained, well beyond any
        // human bridging rate.
        for (int i = 0; i < 10; i++) {
            long tick = i / 2;
            check.onPacketReceive(data, new BlockPlacePacket(data.id(), i, 64, 0, tick));
        }

        assertTrue(violationManager.vl(data.id(), "ScaffoldSpeedA") > 0.0);
    }

    @Test
    void normalBridgingRateNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldSpeedCheck check = new ScaffoldSpeedCheck();
        registry.register(check);

        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        // 10 placements spread across 100 ticks (5s) -> < 2 placements/sec, an ordinary walking
        // bridge pace.
        for (int i = 0; i < 10; i++) {
            check.onPacketReceive(data, new BlockPlacePacket(data.id(), i, 64, 0, i * 10L));
        }

        assertEquals(0.0, violationManager.vl(data.id(), "ScaffoldSpeedA"), 0.001);
    }
}
