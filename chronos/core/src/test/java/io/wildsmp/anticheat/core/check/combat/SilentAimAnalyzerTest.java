package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.player.MovementSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SilentAimAnalyzerTest {

    private static final PositionSnapshot TARGET = new PositionSnapshot(0, 64, 0, 0.3, 1.8, 0L);

    private static Deque<MovementSample> historyFacing(float yaw, float pitch, long tick) {
        Deque<MovementSample> history = new ArrayDeque<>();
        // Attacker at (0,64,-3) - 3 blocks from the target, facing however the caller specifies.
        history.add(new MovementSample(0, 64, -3, yaw, pitch, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0));
        return history;
    }

    @Test
    void aSingleAttackFacingAwayFromTheTargetDoesNotFlagOnItsOwn() {
        SilentAimAnalyzer analyzer = new SilentAimAnalyzer();
        SilentAimAnalyzer.State state = new SilentAimAnalyzer.State();

        // yaw 180 faces -Z, directly away from the target at +Z - about as far off as possible.
        double score = analyzer.analyze(historyFacing(180f, 0f, 0L), 0L, TARGET, state);

        assertEquals(0.0, score, 0.001, "one bad reading alone shouldn't flag - could be a lag artifact");
    }

    @Test
    void repeatedlyAttackingWithoutEverFacingTheTargetFlags() {
        SilentAimAnalyzer analyzer = new SilentAimAnalyzer();
        SilentAimAnalyzer.State state = new SilentAimAnalyzer.State();

        double lastScore = 0.0;
        for (long tick = 0; tick < 4; tick++) {
            // Facing +X (yaw 90) the whole time - never anywhere near the target at +Z.
            lastScore = analyzer.analyze(historyFacing(90f, 0f, tick), tick, TARGET, state);
        }

        assertTrue(lastScore > 0.0, "a sustained pattern of never aiming near the target should flag");
    }

    @Test
    void normallyAimedAttacksNeverFlag() {
        SilentAimAnalyzer analyzer = new SilentAimAnalyzer();
        SilentAimAnalyzer.State state = new SilentAimAnalyzer.State();

        double lastScore = 0.0;
        for (long tick = 0; tick < 5; tick++) {
            // yaw 0 faces +Z, straight at the target - normal legitimate aim.
            lastScore = analyzer.analyze(historyFacing(0f, 0f, tick), tick, TARGET, state);
        }

        assertEquals(0.0, lastScore, 0.001);
    }
}
