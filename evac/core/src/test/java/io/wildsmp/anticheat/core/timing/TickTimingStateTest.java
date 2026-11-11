package io.wildsmp.anticheat.core.timing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickTimingStateTest {

    private static final long PERFECT_TICK_NANOS = 50_000_000L; // 20 TPS

    @Test
    void reportsTargetTpsUnderPerfectTiming() {
        TickTimingState timing = new TickTimingState();
        long now = 0L;
        for (int i = 0; i < 50; i++) {
            timing.onServerTick(now);
            now += PERFECT_TICK_NANOS;
        }

        assertEquals(20.0, timing.currentTps(), 0.01);
        assertEquals(1.0, timing.lagCompensationFactor(), 0.01);
    }

    @Test
    void tpsDropsAndCompensationFactorRisesWhenTicksTakeLonger() {
        TickTimingState timing = new TickTimingState();
        long now = 0L;
        long laggedTickNanos = PERFECT_TICK_NANOS * 2; // effectively 10 TPS

        for (int i = 0; i < 50; i++) {
            timing.onServerTick(now);
            now += laggedTickNanos;
        }

        assertTrue(timing.currentTps() < 15.0, "expected TPS to drop under sustained lag, was " + timing.currentTps());
        assertTrue(timing.lagCompensationFactor() > 1.3,
                "expected compensation factor to rise under lag, was " + timing.lagCompensationFactor());
    }

    @Test
    void tpsNeverReportsAboveTheTwentyTickTarget() {
        TickTimingState timing = new TickTimingState();
        long now = 0L;
        for (int i = 0; i < 50; i++) {
            timing.onServerTick(now);
            now += 10_000_000L;
        }

        assertTrue(timing.currentTps() <= 20.0);
    }
}
