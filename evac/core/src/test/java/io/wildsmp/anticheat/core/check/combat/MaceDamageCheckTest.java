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

class MaceDamageCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        MaceDamageCheck check = new MaceDamageCheck();

        assertEquals("MaceA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.COMBAT, check.category());
    }

    @Test
    void groundedMaceHitWithSmashTierDamageFlagsThroughTheRealViolationPipeline() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);

        CheckRegistry registry = new CheckRegistry(violationManager);
        MaceDamageCheck check = new MaceDamageCheck();
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Standing still on the ground the whole time - never left it, so real fall distance is 0 -
        // yet the server applied smash-tier damage no grounded hit could ever legitimately produce.
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        check.testEvaluate(attacker, 0L, 50.0);

        assertTrue(violationManager.vl(attacker.id(), "MaceA") > 0.0);
    }

    @Test
    void legitimateSmashAttackAfterARealFallNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        CheckRegistry registry = new CheckRegistry(violationManager);
        MaceDamageCheck check = new MaceDamageCheck();
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // Stood on a ledge at y=70, stepped off, and swung the mace mid-fall at y=64 - a genuine
        // 6-block fall, well past the real-fall floor, so the same smash-tier damage here is
        // completely legitimate.
        attacker.recordMovement(new MovementSample(0, 70, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        attacker.recordMovement(new MovementSample(0, 69, 0, 0f, 0f, false, 1, 1, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        attacker.recordMovement(new MovementSample(0, 67, 0, 0f, 0f, false, 2, 2, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, false, 3, 3, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        check.testEvaluate(attacker, 3L, 50.0);

        assertEquals(0.0, violationManager.vl(attacker.id(), "MaceA"), 0.001);
    }

    @Test
    void damageWildlyDisproportionateToARealButModestFallStillFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        CheckRegistry registry = new CheckRegistry(violationManager);
        MaceDamageCheck check = new MaceDamageCheck();
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        // A genuine, real 12-block fall (confirmed live: this exact shape got through an earlier
        // version of this check, which only gated on "did they fall at all," not on whether the
        // damage was proportional to how far) - but 210 damage is wildly beyond what even a
        // maxed-out smash attack from 12 blocks could ever legitimately deal.
        attacker.recordMovement(new MovementSample(0, 76, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        for (long tick = 1; tick <= 11; tick++) {
            attacker.recordMovement(new MovementSample(0, 76 - tick, 0, 0f, 0f, false, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        }
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, false, 12, 12, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        check.testEvaluate(attacker, 12L, 210.0);

        assertTrue(violationManager.vl(attacker.id(), "MaceA") > 0.0);
    }

    @Test
    void groundedHitWithOrdinaryDamageNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);

        CheckRegistry registry = new CheckRegistry(violationManager);
        MaceDamageCheck check = new MaceDamageCheck();
        registry.register(check);

        PlayerData attacker = new PlayerData(UUID.randomUUID(), "Steve");
        attacker.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        // A completely ordinary grounded mace swing - well under the ceiling, no fall involved.
        check.testEvaluate(attacker, 0L, 8.0);

        assertEquals(0.0, violationManager.vl(attacker.id(), "MaceA"), 0.001);
    }
}
