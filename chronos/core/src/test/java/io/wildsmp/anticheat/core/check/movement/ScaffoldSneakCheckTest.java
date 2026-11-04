package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.BlockPlacePacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScaffoldSneakCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        ScaffoldSneakCheck check = new ScaffoldSneakCheck();

        assertEquals("ScaffoldSneakA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void selfSupportingPlacementWhileNotSneakingFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldSneakCheck check = new ScaffoldSneakCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Standing/walking at (0, 100, 0), not sneaking, while blocks appear directly under their
        // own feet - the real scaffold pattern: never breaking stride to sneak first.
        for (int i = 0; i < 5; i++) {
            player.recordMovement(new MovementSample(0, 100, 0, 0f, 0f, true, i, i,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.onPacketReceive(player, new BlockPlacePacket(player.id(), 0, 99, 0, i));
        }

        assertTrue(violationManager.vl(player.id(), "ScaffoldSneakA") > 0.0);
    }

    @Test
    void selfSupportingPlacementWhileActuallySneakingNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldSneakCheck check = new ScaffoldSneakCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects sneaking = new MovementEffects(0, 0, 0, false, false, false, true, false, false, false, false);
        // The real, legitimate manual-bridge pattern: hold shift, place directly beneath your feet.
        for (int i = 0; i < 5; i++) {
            player.recordMovement(new MovementSample(0, 100, 0, 0f, 0f, true, i, i,
                    TerrainKind.NORMAL, sneaking, 0.0));
            check.onPacketReceive(player, new BlockPlacePacket(player.id(), 0, 99, 0, i));
        }

        assertEquals(0.0, violationManager.vl(player.id(), "ScaffoldSneakA"), 0.001);
    }

    @Test
    void placingAwayFromYourOwnFeetNeverFlagsEvenWithoutSneaking() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldSneakCheck check = new ScaffoldSneakCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Ordinary building/placing several blocks away, not under themselves at all - not
        // self-supporting placement, so not sneaking is completely normal here.
        for (int i = 0; i < 5; i++) {
            player.recordMovement(new MovementSample(0, 100, 0, 0f, 0f, true, i, i,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.onPacketReceive(player, new BlockPlacePacket(player.id(), 5, 100, 5, i));
        }

        assertEquals(0.0, violationManager.vl(player.id(), "ScaffoldSneakA"), 0.001);
    }
}
