package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JesusCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        JesusCheck check = new JesusCheck();

        assertEquals("JesusA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void walkingAcrossWaterWithoutSwimmingFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        JesusCheck check = new JesusCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            // onGround true while standing ON TOP of water is itself already unusual - vanilla
            // liquid-surface standing normally reports onGround false - so this reproduces exactly
            // the client-reported state a jesus-hack produces.
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.LIQUID_WATER, MovementEffects.none(), 0.0));
            x += 0.28;
            check.testEvaluate(player);
        }

        assertTrue(violationManager.vl(player.id(), "JesusA") > 0.0);
    }

    @Test
    void legitimateSwimmingAtTheSurfaceNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        JesusCheck check = new JesusCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects swimming = new MovementEffects(0, 0, 0, false, false, false, false, true, false, false, false);
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.LIQUID_WATER, swimming, 0.0));
            x += 0.28;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "JesusA"), 0.001);
    }

    @Test
    void standingStillAtTheWatersEdgeNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        JesusCheck check = new JesusCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.LIQUID_WATER, MovementEffects.none(), 0.0));
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "JesusA"), 0.001);
    }
}
