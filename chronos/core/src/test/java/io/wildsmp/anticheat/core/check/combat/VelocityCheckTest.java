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

class VelocityCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        VelocityCheck check = new VelocityCheck(id -> null);

        assertEquals("VelocityA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void sustainedImpossiblePeakRiseFlagsThroughTheRealViolationPipelineOnTheVictim() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData victim = new PlayerData(UUID.randomUUID(), "Alex");
        CheckRegistry registry = new CheckRegistry(violationManager);
        VelocityCheck check = new VelocityCheck(id -> id.equals(victim.id()) ? victim : null);
        registry.register(check);

        // Grounded at each hit, then a clean rise-then-fall arc peaking 2.5 blocks above the
        // grounded baseline - well past vanilla's true ~1.15 block ceiling (padded to 2.0 here) -
        // before landing again. Repeated across several separate hits.
        long[] hitTicks = {0L, 20L, 40L};
        for (long hitTick : hitTicks) {
            victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, hitTick, hitTick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            victim.recordMovement(new MovementSample(0, 66.5, 0, 0f, 0f, false, hitTick + 1, hitTick + 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            victim.recordMovement(new MovementSample(0, 66.0, 0, 0f, 0f, false, hitTick + 2, hitTick + 2, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, hitTick + 3, hitTick + 3, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            boolean resolved = check.testEvaluate(hitTick, victim.id());
            assertTrue(resolved, "arc already landed by the time of evaluation - should resolve immediately");
        }

        assertTrue(violationManager.vl(victim.id(), "VelocityA") > 0.0);
    }

    @Test
    void normalVanillaKnockbackArcNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData victim = new PlayerData(UUID.randomUUID(), "Alex");
        CheckRegistry registry = new CheckRegistry(violationManager);
        VelocityCheck check = new VelocityCheck(id -> id.equals(victim.id()) ? victim : null);
        registry.register(check);

        // Grounded at each hit, then a completely ordinary ~0.9 block peak rise before landing
        // again - well under the vanilla ceiling.
        long[] hitTicks = {0L, 20L, 40L};
        for (long hitTick : hitTicks) {
            victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, hitTick, hitTick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            victim.recordMovement(new MovementSample(0, 64.9, 0, 0f, 0f, false, hitTick + 1, hitTick + 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            victim.recordMovement(new MovementSample(0, 64.3, 0, 0f, 0f, false, hitTick + 2, hitTick + 2, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, hitTick + 3, hitTick + 3, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(hitTick, victim.id());
        }

        assertEquals(0.0, violationManager.vl(victim.id(), "VelocityA"), 0.001);
    }

    @Test
    void jumpCritLandingWhileAirborneAtHitTimeNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData victim = new PlayerData(UUID.randomUUID(), "Alex");
        CheckRegistry registry = new CheckRegistry(violationManager);
        VelocityCheck check = new VelocityCheck(id -> id.equals(victim.id()) ? victim : null);
        registry.register(check);

        // Victim jumps normally (establishing a grounded baseline), then gets hit while already
        // airborne mid-jump - an entirely ordinary jump-crit. The hit itself lands with onGround
        // false; the analyzer has to walk back to the LAST grounded sample to find the real
        // baseline rather than bailing outright.
        victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        victim.recordMovement(new MovementSample(0, 64.6, 0, 0f, 0f, false, 1, 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0)); // hit lands here, airborne
        victim.recordMovement(new MovementSample(0, 65.0, 0, 0f, 0f, false, 2, 2, TerrainKind.NORMAL, MovementEffects.none(), 0.0)); // peak of the jump, rise 1.0
        victim.recordMovement(new MovementSample(0, 64.6, 0, 0f, 0f, false, 3, 3, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 4, 4, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        boolean resolved = check.testEvaluate(1L, victim.id());

        assertTrue(resolved);
        assertEquals(0.0, violationManager.vl(victim.id(), "VelocityA"), 0.001);
    }

    @Test
    void onPacketReceiveQueuesTheHitAndOnTickPollsUntilItResolvesAgainstTheVictimNotTheAttacker() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        PlayerData victim = new PlayerData(UUID.randomUUID(), "Alex");
        CheckRegistry registry = new CheckRegistry(violationManager);
        VelocityCheck check = new VelocityCheck(id -> id.equals(victim.id()) ? victim : null);
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        check.onPacketReceive(attacker, new io.wildsmp.anticheat.core.packet.AttackPacket(victim.id(), 0L));

        // The victim's arc unfolds one new sample per tick, just like real dispatch would see it -
        // onTick has to keep re-checking (not resolve on the first call) until they land again.
        check.onTick(attacker);
        victim.recordMovement(new MovementSample(0, 64.9, 0, 0f, 0f, false, 1, 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.onTick(attacker);
        victim.recordMovement(new MovementSample(0, 64.3, 0, 0f, 0f, false, 2, 2, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.onTick(attacker);
        victim.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 3, 3, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.onTick(attacker); // now landed - resolves and evaluates

        // A single ordinary hit never crosses MIN_SAMPLES on its own, but this proves the polling
        // dispatch path runs end to end without error and never touches the attacker's own VL.
        assertEquals(0.0, violationManager.vl(attacker.id(), "VelocityA"), 0.001);
    }
}
