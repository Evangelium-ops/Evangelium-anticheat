package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.FallDamagePacket;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoFallCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        NoFallCheck check = new NoFallCheck();

        assertEquals("NoFallA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void fallingFarEnoughToOweRealDamageWithoutTakingAnyFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        NoFallCheck check = new NoFallCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // fallDamagePacket.trackedFallDistance() well past VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS,
        // reported as having taken zero actual damage.
        check.onPacketReceive(player, new FallDamagePacket(player.id(), 10.0, 100L));

        assertTrue(violationManager.vl(player.id(), "NoFallA") > 0.0);
    }
}
