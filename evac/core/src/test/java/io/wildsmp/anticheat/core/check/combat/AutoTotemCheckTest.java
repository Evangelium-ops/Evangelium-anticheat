package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.AutoTotemPacket;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTotemCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        AutoTotemCheck check = new AutoTotemCheck();

        assertEquals("AutoTotemA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void receivingThePacketAlwaysFlagsThroughTheRealViolationPipeline() {
        // Bukkit-side has already confirmed no legitimate inventory action explains the totem
        // before this packet is ever sent - its mere arrival is the violation, no threshold left
        // to apply here.
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        AutoTotemCheck check = new AutoTotemCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        check.onPacketReceive(player, new AutoTotemPacket(player.id(), 100L));

        assertTrue(violationManager.vl(player.id(), "AutoTotemA") > 0.0);
    }

    @Test
    void unrelatedPacketTypesAreIgnored() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        AutoTotemCheck check = new AutoTotemCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        check.onPacketReceive(player, new io.wildsmp.anticheat.core.packet.AttackPacket(player.id(), 0L));

        assertEquals(0.0, violationManager.vl(player.id(), "AutoTotemA"), 0.001);
    }
}
