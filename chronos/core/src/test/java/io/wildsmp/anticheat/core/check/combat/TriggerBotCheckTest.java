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

class TriggerBotCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        TriggerBotCheck check = new TriggerBotCheck(id -> null);

        assertEquals("TriggerBotA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void repeatedlyClickingTheInstantAimArrivesOnTargetFlagsThroughTheRealViolationPipeline() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);

        CheckRegistry registry = new CheckRegistry(violationManager);
        TriggerBotCheck check = new TriggerBotCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        float onTargetPitch = (float) -Math.toDegrees(Math.atan2(64.9 - (64.0 + 1.62), 3.0));

        // Three separate "engagements": aim is clearly off-target, then arrives on-target on the
        // exact tick the attack fires - zero reaction delay, every time.
        long[] attackTicks = {5L, 15L, 25L};
        for (long attackTick : attackTicks) {
            for (long tick = attackTick - 4; tick < attackTick; tick++) {
                targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
                // yaw 90 deg points the ray well away from the target's hitbox here - clearly
                // "off target" (facing straight ahead at yaw 0 would actually still graze the
                // hitbox given this geometry, which would be misread as already on-target).
                attacker.recordMovement(new MovementSample(0, 64, -3, 90f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            }
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, attackTick));
            attacker.recordMovement(new MovementSample(0, 64, -3, 0f, onTargetPitch, true, attackTick, attackTick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(attacker, attackTick, target.id());
        }

        assertTrue(violationManager.vl(attacker.id(), "TriggerBotA") > 0.0);
    }

    @Test
    void normalHumanReactionDelayNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);

        CheckRegistry registry = new CheckRegistry(violationManager);
        TriggerBotCheck check = new TriggerBotCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        float onTargetPitch = (float) -Math.toDegrees(Math.atan2(64.9 - (64.0 + 1.62), 3.0));

        // Aim arrives on-target several ticks before each attack, like a real player who notices
        // the crosshair land, then clicks with normal reaction time (5 ticks = 250ms here).
        long[] attackTicks = {10L, 25L, 40L};
        for (long attackTick : attackTicks) {
            for (long tick = attackTick - 9; tick <= attackTick - 6; tick++) {
                targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
                // See the comment in the test above - yaw 90 is needed to actually clear the
                // hitbox given this test's geometry.
                attacker.recordMovement(new MovementSample(0, 64, -3, 90f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            }
            for (long tick = attackTick - 5; tick <= attackTick; tick++) {
                targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
                attacker.recordMovement(new MovementSample(0, 64, -3, 0f, onTargetPitch, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            }
            check.testEvaluate(attacker, attackTick, target.id());
        }

        assertEquals(0.0, violationManager.vl(attacker.id(), "TriggerBotA"), 0.001);
    }
}
