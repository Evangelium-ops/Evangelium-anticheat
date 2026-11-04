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

class SpiderCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        SpiderCheck check = new SpiderCheck();

        assertEquals("SpiderA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void climbingAVerticalWallWithNoClimbableTerrainFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        double y = 64;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0.3, 0f, 0f, false, tick, tick,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            y += 0.3;
            check.testEvaluate(player);
        }

        assertTrue(violationManager.vl(player.id(), "SpiderA") > 0.0);
    }

    @Test
    void climbingARealLadderNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        double y = 64;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0.3, 0f, 0f, false, tick, tick,
                    TerrainKind.CLIMBABLE, MovementEffects.none(), 0.0));
            y += 0.2;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"), 0.001);
    }

    @Test
    void jumpingStraightUpWithoutDriftingSidewaysStillNeverFlagsWithinTheJumpCeiling() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, 0, 0,
                TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.testEvaluate(player);
        player.recordMovement(new MovementSample(0, 64 + VanillaPhysics.BASE_JUMP_VELOCITY, 0, 0f, 0f, false, 1, 1,
                TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.testEvaluate(player);

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"), 0.001);
    }

    @Test
    void creativeOrSpectatorFlightClimbingAWallNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects flightAllowed = new MovementEffects(0, 0, 0, false, false, false, false, false, false, true, false);
        double y = 64;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0.3, 0f, 0f, false, tick, tick,
                    TerrainKind.NORMAL, flightAllowed, 0.0));
            y += 0.3;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"), 0.001);
    }

    @Test
    void risingAlongAWallWhileInWaterNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Swimming up a pool wall: rises steadily with a little sideways drift, not on the ground and not
        // sprint-swimming. Water explains the rise; this must not look like wall climbing.
        double y = 60;
        for (long tick = 0; tick <= 30; tick++) {
            player.recordMovement(new MovementSample(0.05 * tick, y, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.LIQUID_WATER, MovementEffects.none(), 0.0));
            y += 0.3;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"), 0.001);
    }

    @Test
    void beingLaunchedUpwardByAMobOrWindChargeNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects launched = new MovementEffects(0, 0, 0, false, false, false, false, false, false, false, true);
        double y = 64;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, y, 0.3, 0f, 0f, false, tick, tick,
                    TerrainKind.NORMAL, launched, 0.0));
            y += 0.3;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"));
    }

    @Test
    void staleSuspicionFromEarlierDoesNotFlagALaterBriefRiseAfterStandingOnGround() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long tick = 0;
        double y = 64;
        // two rising samples with nothing explaining them (not enough on their own to flag)...
        for (int i = 0; i < 2; i++) {
            player.recordMovement(new MovementSample(0, y, 0.3, 0f, 0f, false, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            y += 0.3;
            tick++;
            check.testEvaluate(player);
        }
        // ...then a long stretch standing on the ground...
        for (int i = 0; i < 12; i++) {
            player.recordMovement(new MovementSample(0, y, 0.3, 0f, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            tick++;
            check.testEvaluate(player);
        }
        // ...and then ONE odd rising tick (e.g. the first tick of a climb before the terrain is refreshed).
        player.recordMovement(new MovementSample(0, y + 0.3, 0.3, 0f, 0f, false, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        check.testEvaluate(player);

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"));
    }

    @Test
    void repeatedJumpsWithJumpBoostFollowTheirArcAndNeverFlag() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpiderCheck check = new SpiderCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects boosted = new MovementEffects(0, 0, 5, false, false, false, false, false, false, false, false);
        long tick = 0;
        double y = 64;
        for (int jump = 0; jump < 4; jump++) {
            // grounded for a tick, then a full arc: the first rise is the jump velocity, then gravity and drag take over
            player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, true, tick, tick, TerrainKind.NORMAL, boosted, 0.0));
            tick++;
            check.testEvaluate(player);
            double velocity = VanillaPhysics.BASE_JUMP_VELOCITY + 5 * VanillaPhysics.JUMP_VELOCITY_PER_LEVEL_BONUS;
            for (int t = 0; t < 40 && y + velocity > 64; t++) {
                y += velocity;
                player.recordMovement(new MovementSample(0, y, 0, 0f, 0f, false, tick, tick, TerrainKind.NORMAL, boosted, 0.0));
                tick++;
                check.testEvaluate(player);
                velocity = (velocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
            }
            y = 64;
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpiderA"));
    }
}
