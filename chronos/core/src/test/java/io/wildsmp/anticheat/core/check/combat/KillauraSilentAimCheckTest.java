package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillauraSilentAimCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        KillauraSilentAimCheck check = new KillauraSilentAimCheck(id -> null);

        assertEquals("KillauraS", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void repeatedlyHittingWithoutEverFacingTheTargetFlagsThroughTheRealViolationPipeline() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        for (long tick = 0; tick < 5; tick++) {
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraSilentAimCheck check = new KillauraSilentAimCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Attacker at (0,64,-3), always facing +X (yaw 90) - never anywhere near the target at +Z.
        for (long tick = 0; tick < 5; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, -3, 90f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(attacker, tick, target.id());
        }

        assertTrue(violationManager.vl(attacker.id(), "KillauraS") > 0.0);
    }

    @Test
    void normallyAimedAttacksNeverFlag() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        for (long tick = 0; tick < 5; tick++) {
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraSilentAimCheck check = new KillauraSilentAimCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Attacker at (0,64,-3) facing +Z (yaw 0) - straight at the target, normal legitimate aim.
        for (long tick = 0; tick < 5; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, -3, 0f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(attacker, tick, target.id());
        }

        assertEquals(0.0, violationManager.vl(attacker.id(), "KillauraS"), 0.001);
    }
}
