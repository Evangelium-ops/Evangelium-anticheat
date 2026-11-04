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

class ScaffoldCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        ScaffoldCheck check = new ScaffoldCheck();

        assertEquals("ScaffoldA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void placingBlocksFarOutsideTheLookDirectionFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldCheck check = new ScaffoldCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Standing at the origin, facing yaw=0 (looking toward +Z per vanilla's yaw convention),
        // but every block placed is behind them at -Z - nowhere near where they're looking.
        player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0,
                TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        for (int i = 0; i < 5; i++) {
            check.onPacketReceive(player, new BlockPlacePacket(player.id(), 0, 64, -2, i));
        }

        assertTrue(violationManager.vl(player.id(), "ScaffoldA") > 0.0);
    }

    @Test
    void placingBlocksTheyreActuallyLookingAtNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldCheck check = new ScaffoldCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Facing yaw=0 (toward +Z), placing blocks directly ahead and slightly down - exactly the
        // real "bridge in front of yourself while looking down at your feet" pattern.
        player.recordMovement(new MovementSample(0, 65, 0, 0f, 30f, true, 0, 0,
                TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        for (int i = 0; i < 5; i++) {
            check.onPacketReceive(player, new BlockPlacePacket(player.id(), 0, 64, i + 3, i));
        }

        assertEquals(0.0, violationManager.vl(player.id(), "ScaffoldA"), 0.001);
    }

    @Test
    void placingWellBeyondReachFlagsEvenIfRoughlyOnAngle() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        ScaffoldCheck check = new ScaffoldCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0,
                TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        // Directly ahead on angle, but 20 blocks away - far beyond any real placement reach.
        for (int i = 0; i < 5; i++) {
            check.onPacketReceive(player, new BlockPlacePacket(player.id(), 0, 64, 20, i));
        }

        assertTrue(violationManager.vl(player.id(), "ScaffoldA") > 0.0);
    }
}
