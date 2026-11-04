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

class TimerCheckTest {

    private static final long ONE_TICK_NANOS = 50_000_000L; // 50ms

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        TimerCheck check = new TimerCheck();

        assertEquals("TimerA", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void packetsArrivingConsistentlyTwiceAsFastAsRealTimeFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long nanoTime = 0;
        // 25ms between packets = twice the legitimate rate, sustained for 15 real seconds.
        for (long tick = 0; tick <= 600; tick++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS / 2;
            check.testEvaluate(player);
        }

        assertTrue(violationManager.vl(player.id(), "TimerA") > 0.0);
    }

    @Test
    void normalPacketCadenceNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long nanoTime = 0;
        for (long tick = 0; tick <= 400; tick++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "TimerA"), 0.001);
    }

    @Test
    void aBurstOfPacketsRightAfterAWindChargeLaunchNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        MovementEffects launched = new MovementEffects(0, 0, 0, false, false, false, false, false, false, false, true);
        long nanoTime = 0;
        // Same real-time-vs-tick-count ratio as the flagging test above - packets arriving twice
        // as fast as the tick count implies - but with recentlyLaunched set, matching a client
        // resolving a wind-charge/explosion launch in a tight real-time burst.
        for (long tick = 0; tick <= 10; tick++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, false, tick, nanoTime,
                    TerrainKind.AIR, launched, 0.0));
            nanoTime += ONE_TICK_NANOS / 2;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "TimerA"), 0.001);
    }

    @Test
    void serverTickCounterJumpingTwoAtATimeNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        // Reproduces the live false flag: the client sends exactly one packet per 50ms, but the
        // server's own tick counter lags and then catches up, stamping consecutive packets with
        // tick deltas of 2, 0, 2, 0 (and 2,1,2,1 stretches). The player did nothing wrong.
        long nanoTime = 0;
        long tick = 0;
        for (int i = 0; i <= 200; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS;
            tick += (i % 2 == 0) ? 2 : (i % 8 == 1 ? 0 : 1);
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "TimerA"), 0.001);
    }

    @Test
    void aBriefNetworkStallFollowedByABurstNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long nanoTime = 0;
        long tick = 0;
        // 3s of normal cadence, then a 750ms gap where nothing arrives (a network hiccup), then the
        // queued packets arriving 4ms apart, then normal cadence again.
        for (int i = 0; i < 60; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick++, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS;
            check.testEvaluate(player);
        }
        nanoTime += 750_000_000L;
        for (int i = 0; i < 15; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick++, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += 4_000_000L;
            check.testEvaluate(player);
        }
        for (int i = 0; i < 60; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick++, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "TimerA"), 0.001);
    }

    @Test
    void aThreeSecondFreezeFollowedByAFullCatchUpBurstNeverFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long nanoTime = 0;
        long tick = 0;
        // 10s of normal cadence, then a 3s freeze (a lag spike, a ping hiccup), then the whole backlog of 60 packets arriving
        // 4ms apart, then normal cadence again: the reported "running normally, flagged TimerA" case.
        for (int i = 0; i < 200; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick++, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS;
            check.testEvaluate(player);
        }
        nanoTime += 3_000_000_000L;
        for (int i = 0; i < 60; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick++, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += 4_000_000L;
            check.testEvaluate(player);
        }
        for (int i = 0; i < 200; i++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick++, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += ONE_TICK_NANOS;
            check.testEvaluate(player);
        }

        assertEquals(0.0, violationManager.vl(player.id(), "TimerA"), 0.001);
    }

    @Test
    void aSustainedOnePointSixTimesSpeedupStillFlags() {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0));
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry registry = new CheckRegistry(violationManager);
        TimerCheck check = new TimerCheck();
        registry.register(check);

        PlayerData player = new PlayerData(UUID.randomUUID(), "Steve");
        long nanoTime = 0;
        for (long tick = 0; tick <= 700; tick++) {
            player.recordMovement(new MovementSample(0, 64, 0, 0f, 0f, true, tick, nanoTime,
                    TerrainKind.NORMAL, MovementEffects.none(), 0.0));
            nanoTime += 31_000_000L; // 1.6x the legitimate rate
            check.testEvaluate(player);
        }

        assertTrue(violationManager.vl(player.id(), "TimerA") > 0.0);
    }
}
