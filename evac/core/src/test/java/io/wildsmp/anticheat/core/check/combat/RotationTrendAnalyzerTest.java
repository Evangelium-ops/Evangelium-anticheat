package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.player.MovementSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationTrendAnalyzerTest {

    private static MovementSample at(long tick, float yaw, float pitch) {
        return new MovementSample(0, 64, 0, yaw, pitch, true, tick, tick, TerrainKind.NORMAL, MovementEffects.none(), 0.0);
    }

    @Test
    void smoothHumanLikeRotationScoresLow() {
        RotationTrendAnalyzer analyzer = new RotationTrendAnalyzer();
        Deque<MovementSample> history = new ArrayDeque<>();
        // gradual, steady turn toward a target over several ticks, continuing smoothly afterward -
        // no snap, no revert
        for (int i = 0; i <= 10; i++) {
            history.add(at(i, i * 3f, 0f));
        }

        double score = analyzer.analyze(history, 5L, new RotationTrendAnalyzer.State());

        assertTrue(score < 0.3, "expected a low score for smooth rotation, was " + score);
    }

    @Test
    void instantSnapThenRevertScoresHigh() {
        RotationTrendAnalyzer analyzer = new RotationTrendAnalyzer();
        Deque<MovementSample> history = new ArrayDeque<>();
        // small, steady natural jitter before the attack
        history.add(at(0, 10f, 0f));
        history.add(at(1, 10.5f, 0f));
        history.add(at(2, 10.2f, 0f));
        history.add(at(3, 10.6f, 0f));
        // instant large snap at the attack tick...
        history.add(at(4, 80f, 0f));
        // ...then reverts most of the way back afterward, rather than continuing to track
        history.add(at(5, 30f, 0f));
        history.add(at(6, 12f, 0f));
        history.add(at(7, 10.5f, 0f));

        double score = analyzer.analyze(history, 4L, new RotationTrendAnalyzer.State());

        assertTrue(score > 0.5, "expected a high score for a snap-then-revert pattern, was " + score);
    }

    @Test
    void continuedTrackingIntoTheNextComboAttackDoesNotLookLikeARevert() {
        RotationTrendAnalyzer analyzer = new RotationTrendAnalyzer();
        Deque<MovementSample> history = new ArrayDeque<>();
        history.add(at(0, 10f, 0f));
        history.add(at(1, 10.5f, 0f));
        history.add(at(2, 10.2f, 0f));
        history.add(at(3, 10.6f, 0f));
        history.add(at(4, 80f, 0f));  // attack #1's snap onto the target
        history.add(at(5, 85f, 0f)); // still tracking the same direction after the hit...
        history.add(at(6, 20f, 0f)); // ...but attack #2 (a combo hit) snaps onto a new position here
        history.add(at(7, 18f, 0f));

        // Without bounding the lookahead at the next queued attack's tick, attack #1's "after"
        // window would reach into attack #2's own snap and look like a reversal even though attack
        // #1 itself was never undone - just followed by another legitimate attack.
        double score = analyzer.analyze(history, 4L, 6L, new RotationTrendAnalyzer.State());

        assertTrue(score < 0.5, "continued tracking into the next combo attack should not score as a revert, was " + score);
    }

    @Test
    void suspiciouslyIdenticalSnapMagnitudeAcrossManyAttacksRaisesTheScore() {
        RotationTrendAnalyzer analyzer = new RotationTrendAnalyzer();
        RotationTrendAnalyzer.State state = new RotationTrendAnalyzer.State();
        double lastScore = 0.0;

        // simulate 8 separate "attacks", each with an identical-magnitude snap - a real player's
        // snap magnitude varies noticeably attack to attack
        for (int attack = 0; attack < 8; attack++) {
            Deque<MovementSample> history = new ArrayDeque<>();
            history.add(at(0, 10f, 0f));
            history.add(at(1, 10.1f, 0f));
            history.add(at(2, 45f, 0f)); // identical 35-degree snap every time
            history.add(at(3, 44.8f, 0f));

            lastScore = analyzer.analyze(history, 2L, state);
        }

        assertTrue(lastScore > 0.0, "expected the consistency signal to contribute once enough history exists");
    }
}
