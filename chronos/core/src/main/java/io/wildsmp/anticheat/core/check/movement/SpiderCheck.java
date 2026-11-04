package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.packet.MovementPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.Deque;

public final class SpiderCheck extends Check {

    private static final String STATE_KEY = "SpiderCheck.State";
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double CORRECTION_CONFIDENCE_THRESHOLD = 0.8;
    private static final double VL_SCALE = 5.0;

    private final SpiderAnalyzer analyzer;
    private final MovementCorrectionSink correctionSink;

    public SpiderCheck() {
        this(new SpiderAnalyzer(new PredictedMovementEngine()), null);
    }

    public SpiderCheck(MovementCorrectionSink correctionSink) {
        this(new SpiderAnalyzer(new PredictedMovementEngine()), correctionSink);
    }

    public SpiderCheck(SpiderAnalyzer analyzer) {
        this(analyzer, null);
    }

    public SpiderCheck(SpiderAnalyzer analyzer, MovementCorrectionSink correctionSink) {
        super("SpiderA", CheckCategory.MOVEMENT);
        this.analyzer = analyzer;
        this.correctionSink = correctionSink;
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof MovementPacket)) {
            return;
        }
        evaluate(data);
    }

    private void evaluate(PlayerData data) {
        Deque<MovementSample> history = data.movementHistory();
        if (history.size() < 2) {
            return;
        }
        MovementSample current = history.peekLast();
        MovementSample previous = null;
        for (MovementSample sample : history) {
            if (sample == current) {
                break;
            }
            previous = sample;
        }
        if (previous == null) {
            return;
        }
        SpiderAnalyzer.State state = data.computeCheckState(STATE_KEY, SpiderAnalyzer.State::new);
        double score = analyzer.analyze(previous, current, state);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("spider score %.2f", score));
        }
        if (score >= CORRECTION_CONFIDENCE_THRESHOLD && correctionSink != null) {
            correctionSink.correctPosition(data.id(), previous.x(), previous.y(), previous.z());
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data) {
        evaluate(data);
    }
}
