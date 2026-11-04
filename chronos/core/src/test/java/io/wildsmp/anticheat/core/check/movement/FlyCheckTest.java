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

class FlyCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        FlyCheck check = new FlyCheck();

        assertEquals("FlyA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void sustainedUnsupportedHeightGainNeverExplainedByAJumpFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Already airborne with no prior grounded sample in view (history starts mid-air), gaining
        // a full block of height every tick, never falling - no jump arc explains this at all.
        double y = 70;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.AIR, MovementEffects.none(), 0.0));
            y += 1.0;
            check.testEvaluate(player);
        }

        assertTrue(violationManager.vl(player.id(), "FlyA") > 0.0);
    }

    @Test
    void aRealJumpsFullDeceleratingArcNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        double y = 64;
        double velocity = 0.0;
        boolean onGround = true;
        for (long tick = 0; tick <= 20; tick++) {
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, onGround, tick, tick,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            check.testEvaluate(player);
            if (tick == 0) {
                velocity = VanillaPhysics.BASE_JUMP_VELOCITY; // jump on the very first tick
                onGround = false;
            } else if (!onGround) {
                velocity = (velocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
            }
            y += velocity;
            if (velocity < 0 && y <= 64) {
                y = 64;
                onGround = true;
            }
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void jumpingWithJumpBoostTwoWithinTheBoostedCeilingNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects jumpBoostTwo = new MovementEffects(0, 0, 2, false, false, false, false, false, false, false, false);
        double boostedVelocity = VanillaPhysics.BASE_JUMP_VELOCITY + 2 * VanillaPhysics.JUMP_VELOCITY_PER_LEVEL_BONUS;

        player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0,
                TerrainKind.NORMAL, jumpBoostTwo, 0.0));
        check.testEvaluate(player);
        player.recordMovement(new MovementSample(0, 64 + boostedVelocity, 0, 0f, 0f, false, 1, 1,
                TerrainKind.NORMAL, jumpBoostTwo, 0.0));
        check.testEvaluate(player);

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void creativeOrSpectatorFlightNeverFlagsEvenWithSustainedUnsupportedRise() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects flightAllowed = new MovementEffects(0, 0, 0, false, false, false, false, false, false, true, false);
        double y = 70;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.AIR, flightAllowed, 0.0));
            y += 1.0;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void aWindChargeOrExplosionLaunchNeverFlagsEvenWithASuddenBigRise() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects launched = new MovementEffects(0, 0, 0, false, false, false, false, false, false, false, true);
        // A wind charge/explosion launch rises far faster than any legitimate jump - would flag
        // FlyA outright without the recentlyLaunched exemption.
        double y = 64;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.AIR, launched, 0.0));
            y += 1.5;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void floatingOrWadingInWaterWithoutSprintSwimmingNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Treading water at the surface: not on the ground, not "swimming" (that flag only means sprint-swimming),
        // barely moving vertically, for far longer than any jump lasts. Must not look like flying.
        for (long tick = 0; tick <= 120; tick++) {
            player.recordMovement(new MovementSample(0, 63.0 + (tick % 2) * 0.02, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.LIQUID_WATER, MovementEffects.none(), 0.0));
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void aSlowFallingPlayerDriftingDownSlowlyNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects slowFalling = new MovementEffects(0, 0, 0, false, true, false, false, false, false, false, false);
        // Slow Falling caps the fall at ~0.01 blocks a tick: far slower than vanilla gravity predicts, for a long time.
        double y = 200;
        for (long tick = 0; tick <= 120; tick++) {
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.AIR, slowFalling, 0.0));
            y -= 0.01;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void walkingAlongALakeBedWithNoGroundContactReportedNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Submerged and resting on the bottom: the client reports onGround=false the whole time.
        for (long tick = 0; tick <= 120; tick++) {
            player.recordMovement(new MovementSample(0.1 * tick, 52.0, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.SUBMERGED, MovementEffects.none(), 0.0));
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void aJumpWithExtraStatusOnlyPacketsInTheSameTickNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // A normal jump arc, but every packet is followed by a second one for the same tick at the same spot (a status-only
        // packet). The duplicates must not count as extra airborne time or as "stopped rising".
        double[] arc = {0.42, 0.333, 0.248, 0.165, 0.083, 0.0, -0.078, -0.155, -0.23, -0.3, -0.37};
        double y = 64;
        long tick = 0;
        player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.testEvaluate(player);
        for (double dy : arc) {
            tick++;
            y += dy;
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick * 50_000_000L,
                    TerrainKind.AIR, MovementEffects.none(), 0.0));
            check.testEvaluate(player);
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick * 50_000_000L + 1_000_000L,
                    TerrainKind.AIR, MovementEffects.none(), 0.0));
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"), 0.001);
    }

    @Test
    void staleSuspicionFromEarlierDoesNotFlagALaterBriefHopAfterStandingOnGround() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        FlyCheck check = new FlyCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long tick = 0;
        double y = 64;
        for (int i = 0; i < 2; i++) { // two rising samples with nothing explaining them
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            y += 1.0;
            tick++;
            check.testEvaluate(player);
        }
        for (int i = 0; i < 12; i++) { // then a long stretch standing on the ground
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            tick++;
            check.testEvaluate(player);
        }
        // then one odd rising tick
        player.recordMovement(new MovementSample(0, y + 1.0, 0, 0f, 0f, false, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.testEvaluate(player);

        assertEquals(0.0, violationManager.vl(player.id(), "FlyA"));
    }
}
