package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.player.MovementSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenAimAnalyzerTest {

    // The target genuinely left (or entered) the frozen aim's real hitbox coverage over the window.
    private static final boolean TRACKING_REQUIRED = true;
    private static final boolean TRACKING_NOT_REQUIRED = false;

    private static Deque<MovementSample> frozenHistoryUpTo(long attackTick) {
        Deque<MovementSample> history = new ArrayDeque<>();
        // Bit-for-bit identical yaw/pitch on every tick for a full second - no real mouse/
        // controller input holds this still.
        for (long tick = attackTick - 20; tick <= attackTick; tick++) {
            history.add(new MovementSample(0, 64, 0, 604.141f, 8.551f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        }
        return history;
    }

    private static Deque<MovementSample> jitteryHistoryUpTo(long attackTick) {
        Deque<MovementSample> history = new ArrayDeque<>();
        for (long tick = attackTick - 20; tick <= attackTick; tick++) {
            // Small but real natural jitter each tick, like genuine mouse input.
            float jitter = (tick % 2 == 0) ? 0.3f : -0.2f;
            history.add(new MovementSample(0, 64, 0, 10f + jitter, 0f, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        }
        return history;
    }

    @Test
    void aSinglyFrozenWindowDoesNotFlagOnItsOwn() {
        FrozenAimAnalyzer analyzer = new FrozenAimAnalyzer();
        FrozenAimAnalyzer.State state = new FrozenAimAnalyzer.State();

        double score = analyzer.analyze(frozenHistoryUpTo(20L), 20L, TRACKING_REQUIRED, state);

        assertEquals(0.0, score, 0.001, "one frozen reading alone shouldn't flag - not enough history to be sure");
    }

    @Test
    void sustainedFrozenCameraWhileTargetMovesFlags() {
        FrozenAimAnalyzer analyzer = new FrozenAimAnalyzer();
        FrozenAimAnalyzer.State state = new FrozenAimAnalyzer.State();

        double lastScore = 0.0;
        // One reading per tick that needed tracking, as the continuous caller records them: the
        // camera stays bit-for-bit frozen for 40 of them (2 real seconds on top of the 1s window).
        for (long tick = 20L; tick < 60L; tick++) {
            lastScore = analyzer.analyze(frozenHistoryUpTo(tick), tick, TRACKING_REQUIRED, state);
        }

        assertTrue(lastScore > 0.0, "a sustained completely static camera while the target moves should flag");
    }

    @Test
    void frozenCameraWithAStationaryTargetNeverFlags() {
        FrozenAimAnalyzer analyzer = new FrozenAimAnalyzer();
        FrozenAimAnalyzer.State state = new FrozenAimAnalyzer.State();

        double lastScore = 0.0;
        // The camera is just as frozen as the flagging case above, but the target never moved
        // enough to require any tracking - holding still is completely ordinary here.
        for (long tick = 20L; tick < 60L; tick++) {
            lastScore = analyzer.analyze(frozenHistoryUpTo(tick), tick, TRACKING_NOT_REQUIRED, state);
        }

        assertEquals(0.0, lastScore, 0.001,
                "a frozen camera against a target that wasn't moving isn't suspicious - real mice don't jitter at rest");
    }

    @Test
    void realHumanJitterNeverFlags() {
        FrozenAimAnalyzer analyzer = new FrozenAimAnalyzer();
        FrozenAimAnalyzer.State state = new FrozenAimAnalyzer.State();

        double lastScore = 0.0;
        for (long tick = 20L; tick < 60L; tick++) {
            lastScore = analyzer.analyze(jitteryHistoryUpTo(tick), tick, TRACKING_REQUIRED, state);
        }

        assertEquals(0.0, lastScore, 0.001);
    }

    @Test
    void aBriefFrozenSpellWellUnderTheSustainedStreakNeverFlags() {
        FrozenAimAnalyzer analyzer = new FrozenAimAnalyzer();
        FrozenAimAnalyzer.State state = new FrozenAimAnalyzer.State();

        double lastScore = 0.0;
        // Not touching the mouse for ~half a second of a fight (plus the 1s window) is ordinary
        // human behaviour - the old 8-reading/50% setting flagged exactly this on real players.
        for (long tick = 20L; tick < 28L; tick++) {
            lastScore = analyzer.analyze(frozenHistoryUpTo(tick), tick, TRACKING_REQUIRED, state);
        }

        assertEquals(0.0, lastScore, 0.001);
    }

    @Test
    void resetForgetsTheStreakSoItCannotCarryIntoTheNextFight() {
        FrozenAimAnalyzer analyzer = new FrozenAimAnalyzer();
        FrozenAimAnalyzer.State state = new FrozenAimAnalyzer.State();
        for (long tick = 20L; tick < 60L; tick++) {
            analyzer.analyze(frozenHistoryUpTo(tick), tick, TRACKING_REQUIRED, state);
        }

        state.reset();
        double scoreRightAfterReset = analyzer.analyze(frozenHistoryUpTo(60L), 60L, TRACKING_REQUIRED, state);

        assertEquals(0.0, scoreRightAfterReset, 0.001);
    }
}
