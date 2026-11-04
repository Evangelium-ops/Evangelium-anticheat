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

class SpeedCheckTest {

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        SpeedCheck check = new SpeedCheck();

        assertEquals("SpeedA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void sustainedImpossibleHorizontalSpeedFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // 2 blocks/tick horizontally on flat, normal, unenchanted ground - far beyond even the
        // padded sprint ceiling (~0.42 blocks/tick) - repeated across several ticks.
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            x += 2.0;
            check.testEvaluate(player);
        }

        assertTrue(violationManager.vl(player.id(), "SpeedA") > 0.0);
    }

    @Test
    void normalSprintingNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects sprinting = new MovementEffects(0, 0, 0, false, false, true, false, false, false, false, false);
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.NORMAL, sprinting, 0.0));
            x += 0.28; // just under the real documented sprint steady-state speed
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpeedA"), 0.001);
    }

    @Test
    void sprintingOnIceNeverFlagsEvenThoughItsFasterThanNormalSprint() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects sprinting = new MovementEffects(0, 0, 0, false, false, true, false, false, false, false, false);
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.ICE, sprinting, 0.0));
            x += 0.5; // faster than normal-ground sprint, legitimate on ice
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpeedA"), 0.001);
    }

    @Test
    void sprintJumpingNeverFlagsSpeedEvenWithVanillasRealHorizontalJumpBoost() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects sprinting = new MovementEffects(0, 0, 0, false, false, true, false, false, false, false, false);
        double x = 0;
        boolean onGround = true;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, onGround, tick, tick,
                    TerrainKind.NORMAL, sprinting, 0.0));
            // Vanilla's real sprint-jump gives a horizontal lunge well beyond the padded sprint
            // ceiling (~0.42) the instant a sprinting player leaves the ground - this used to
            // false-flag SpeedA every time a player sprint-jumped.
            x += onGround ? 0.28 : 0.6;
            if (tick == 0) {
                onGround = false; // leaves the ground on the jump tick
            }
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpeedA"), 0.001);
    }

    @Test
    void flyingFastHorizontallyWhileAirborneIsFlyAsTerritoryNotSpeedAs() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Airborne (never on ground), moving fast both horizontally and vertically - a classic fly
        // hack. This is FlyCheck's signal to catch (see FlyCheckTest); SpeedA must stay silent.
        double x = 0;
        double y = 70;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, y, 0, 0f, 0f, false, tick, tick,
                    TerrainKind.AIR, MovementEffects.none(), 0.0));
            x += 1.0;
            y += 1.0;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpeedA"), 0.001);
    }

    @Test
    void pluginLaunchedPlayerMovingFastOnTheGroundNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // A plugin ability (dash/launch) gives the server-driven velocity impulse - the server
        // marks it via recentlyLaunched, so the same impossible-looking displacement is legitimate.
        MovementEffects launched = new MovementEffects(0, 0, 0, false, false, false, false, false, false, false, true);
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.NORMAL, launched, 0.0));
            x += 2.0;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpeedA"), 0.001);
    }

    @Test
    void flightAllowedPlayerMovingFastOnTheGroundNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        SpeedCheck check = new SpeedCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects flying = new MovementEffects(0, 0, 0, false, false, false, false, false, false, true, false);
        double x = 0;
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(x, 64, 0, 0f, 0f, true, tick, tick,
                    TerrainKind.NORMAL, flying, 0.0));
            x += 2.0;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "SpeedA"), 0.001);
    }
}
