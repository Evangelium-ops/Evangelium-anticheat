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

class HitboxCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        HitboxCheck check = new HitboxCheck(id -> null);

        assertEquals("Hitboxes", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void repeatedlyHittingWithoutTheRayEverTouchingTheRealBoxFlagsThroughTheRealViolationPipeline() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        // Target's true hitbox spans x:[-0.3,0.3] y:[64,65.8] z:[-0.3,0.3].
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        for (long tick = 0; tick < 12; tick++) {
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        HitboxCheck check = new HitboxCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Attacker at (0,64,-3), facing yaw 30 degrees off dead-ahead - aimed well past the
        // target's narrow 0.6-wide box at this range, so the ray never actually touches it, even
        // though a generous angular threshold alone might not catch a miss this "small-looking".
        for (long tick = 0; tick < 12; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, -3, 30f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(attacker, tick, target.id());
        }

        assertTrue(violationManager.vl(attacker.id(), "Hitboxes") > 0.0);
    }

    @Test
    void aimedDirectlyAtTheRealHitboxNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        for (long tick = 0; tick < 12; tick++) {
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        HitboxCheck check = new HitboxCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Attacker at (0,64,-3) facing yaw 0 (straight at +Z, dead-on for the target's x/z center)
        // with a slight downward pitch to land the ray within the target's real vertical span.
        float pitch = (float) -Math.toDegrees(Math.atan2(64.9 - (64.0 + 1.62), 3.0));
        for (long tick = 0; tick < 12; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, -3, 0f, pitch, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(attacker, tick, target.id());
        }

        assertEquals(0.0, violationManager.vl(attacker.id(), "Hitboxes"), 0.001);
    }
}
