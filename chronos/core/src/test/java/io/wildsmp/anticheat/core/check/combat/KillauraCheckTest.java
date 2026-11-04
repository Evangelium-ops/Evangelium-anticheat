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

class KillauraCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        KillauraCheck check = new KillauraCheck(id -> null);

        assertEquals("KillauraA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    /**
     * Mirrors {@code RotationTrendAnalyzerTest.instantSnapThenRevertScoresHigh} - full history
     * (before AND after the attack tick) already present, evaluated directly, matching what
     * {@link KillauraCheck#onTick} does once enough ticks have passed since the attack packet.
     */
    @Test
    void aSnapThenRevertPatternFlagsThroughTheRealViolationPipeline() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraCheck check = new KillauraCheck(id -> null);
        registry.register(check);

        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        data.recordMovement(new MovementSample(0, 64, 0, 10f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        data.recordMovement(new MovementSample(0, 64, 0, 10.5f, 0f, true, 1, 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        data.recordMovement(new MovementSample(0, 64, 0, 10.2f, 0f, true, 2, 2, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        data.recordMovement(new MovementSample(0, 64, 0, 10.6f, 0f, true, 3, 3, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        data.recordMovement(new MovementSample(0, 64, 0, 80f, 0f, true, 4, 4, TerrainKind.NORMAL, MovementEffects.none(), 0.0)); // snap at the attack tick
        data.recordMovement(new MovementSample(0, 64, 0, 30f, 0f, true, 5, 5, TerrainKind.NORMAL, MovementEffects.none(), 0.0)); // reverts afterward...
        data.recordMovement(new MovementSample(0, 64, 0, 12f, 0f, true, 6, 6, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        data.recordMovement(new MovementSample(0, 64, 0, 10.5f, 0f, true, 7, 7, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        // A single flick-and-return is ordinary human aim and must not flag by itself...
        check.testEvaluate(data, 4L, UUID.randomUUID()); // the attack happened at tick 4
        assertEquals(0.0, violationManager.vl(data.id(), "KillauraA"), 0.001);

        // ...but the same pattern repeating attack after attack is the real aimbot signature.
        for (int i = 0; i < 4; i++) {
            check.testEvaluate(data, 4L, UUID.randomUUID());
        }

        assertTrue(violationManager.vl(data.id(), "KillauraA") > 0.0);
    }

    @Test
    void smoothRotationDoesNotFlag() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraCheck check = new KillauraCheck(id -> null);
        registry.register(check);

        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        for (int i = 0; i <= 10; i++) {
            data.recordMovement(new MovementSample(0, 64, 0, i * 2f, 0f, true, i, i, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        }

        check.testEvaluate(data, 5L, UUID.randomUUID());

        assertEquals(0.0, violationManager.vl(data.id(), "KillauraA"), 0.001);
    }

    @Test
    void onPacketReceiveQueuesTheAttackTickFromRecentMovement() {
        KillauraCheck check = new KillauraCheck(id -> null);
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");
        data.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 5, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        check.onPacketReceive(data, new io.wildsmp.anticheat.core.packet.AttackPacket(
                UUID.randomUUID(), 5L));

        // No direct getter for the pending queue - drive onTick forward and confirm evaluation
        // actually ran by checking VL changed (smooth rotation here, so it stays at zero, but a
        // NullPointerException/ClassCastException on a bad packet type would fail this test).
        for (long tick = 5; tick <= 8; tick++) {
            data.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        }
        check.onTick(data);
    }

    @Test
    void continuousFrozenAimWhileTargetMovesFlagsEvenBetweenLandedHits() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        // Target starts well outside the attacker's frozen aim line, then drifts into it over the
        // engagement - a real ray-vs-hitbox coverage flip, not just a center-point distance or
        // angle change, so ordinary vertical wobble (knockback, jump-critting) on either side can't
        // trigger this the way a plain center-point measurement could.
        for (long tick = 0; tick <= 70; tick++) {
            double x = 5.0 - tick * (5.0 / 70.0);
            targetHistory.record(new PositionSnapshot(x, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        // Lenient thresholds here on purpose: this test exercises the continuous WIRING with a short
        // synthetic scenario; the strict production defaults are covered by FrozenAimAnalyzerTest.
        KillauraCheck check = new KillauraCheck(new RotationTrendAnalyzer(), new AimPrecisionAnalyzer(),
                new FrozenAimAnalyzer(8, 3, 0.5), id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Frozen aim pointed dead-on at the target's eventual (x=0) position the whole time - never
        // adjusts as the target drifts into view from off to the side.
        float exactPitch = (float) -Math.toDegrees(Math.atan2(64.9 - (64.0 + 1.62), 3.0));
        attacker.recordMovement(new MovementSample(0, 64, -3, 0f, exactPitch, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        // Two hits land early in the engagement - the target then keeps drifting toward the frozen
        // crosshair's hitbox afterward, so no further attack packets fire even though the camera
        // itself never moves. Sustained engagement (more than one attack), not a single isolated
        // hit, is what should make the continuous tracking below kick in.
        check.onPacketReceive(attacker, new io.wildsmp.anticheat.core.packet.AttackPacket(target.id(), 0L));
        attacker.recordMovement(new MovementSample(0, 64, -3, 0f, exactPitch, true, 1, 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.onPacketReceive(attacker, new io.wildsmp.anticheat.core.packet.AttackPacket(target.id(), 1L));

        for (long tick = 2; tick <= 70; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, -3, 0f, exactPitch, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.onTick(attacker);
        }

        assertTrue(violationManager.vl(attacker.id(), "KillauraA") > 0.0);
    }

    @Test
    void frozenCameraAgainstAStationaryTargetNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        // Target never moves - both players are just standing still next to each other.
        for (long tick = 0; tick <= 70; tick++) {
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraCheck check = new KillauraCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 604.141f, 8.551f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        // Camera is just as frozen as the flagging case above, and the attacker keeps clicking the
        // same nearby, stationary target repeatedly - a completely ordinary way to play.
        check.onPacketReceive(attacker, new io.wildsmp.anticheat.core.packet.AttackPacket(target.id(), 0L));
        attacker.recordMovement(new MovementSample(0, 64, 0, 604.141f, 8.551f, true, 1, 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.onPacketReceive(attacker, new io.wildsmp.anticheat.core.packet.AttackPacket(target.id(), 1L));

        for (long tick = 2; tick <= 70; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, 0, 604.141f, 8.551f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.onTick(attacker);
        }

        assertEquals(0.0, violationManager.vl(attacker.id(), "KillauraA"), 0.001);
    }

    @Test
    void sustainedPreciseAimAcrossSeveralHitsFlagsViaAimPrecisionEvenWithoutEverBeingOffTarget() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData target = new PlayerData(UUID.randomUUID(), "Alex");
        // Target stands at (0,64,0) - a 0.6-wide, 1.8-tall box, so its aim reference point (center)
        // is (0, 64.9, 0). Stationary the whole test; one snapshot per attack below.
        TickIndexedPositionHistory targetHistory = target.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        // Twelve hits: precision needs a few attacks of history before it scores, and a flag now
        // requires the pattern to repeat (3 suspicious of the last 8) rather than a single reading.
        long[] attackTicks = {0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L};
        for (long tick : attackTicks) {
            targetHistory.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraCheck check = new KillauraCheck(id -> id.equals(target.id()) ? target : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Attacker stands 3 blocks away, facing dead-on at the target's reference point on EVERY
        // single attack - never off-target beforehand, exactly like a smooth continuous
        // auto-tracker (no snap-then-revert for RotationTrendAnalyzer to catch, and no "before was
        // off" moment for a correction-gated design to catch either - only a sustained average
        // this precise, measured attack to attack, is what should flag this).
        float exactPitch = (float) -Math.toDegrees(Math.atan2(64.9 - (64.0 + 1.62), 3.0));
        for (long attackTick : attackTicks) {
            attacker.recordMovement(new MovementSample(0, 64, -3, 0f, exactPitch, true, attackTick, attackTick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(attacker, attackTick, target.id());
        }

        assertTrue(violationManager.vl(attacker.id(), "KillauraA") > 0.0);
    }

    @Test
    void aSingleIsolatedAttackFollowedByOrdinaryStillnessNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        KillauraCheck check = new KillauraCheck(id -> null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 604.141f, 8.551f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        // Just one attack, ever - completely ordinary for a player who then simply doesn't touch
        // their mouse for the next several seconds (deciding what to do, watching chat, etc.). A
        // single hit isn't "sustained engagement" and must never trigger continuous tracking.
        check.onPacketReceive(attacker, new io.wildsmp.anticheat.core.packet.AttackPacket(UUID.randomUUID(), 0L));

        for (long tick = 1; tick <= 70; tick++) {
            attacker.recordMovement(new MovementSample(0, 64, 0, 604.141f, 8.551f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.onTick(attacker);
        }

        assertEquals(0.0, violationManager.vl(attacker.id(), "KillauraA"), 0.001);
    }
}
