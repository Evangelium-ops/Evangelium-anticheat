package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.packet.MovementPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.Deque;

public final class TimerCheck extends Check {

    private static final String STATE_KEY = "TimerCheck.State";
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 8.0;

    private final TimerAnalyzer analyzer;

    public TimerCheck() {
        this(new TimerAnalyzer());
    }

    public TimerCheck(TimerAnalyzer analyzer) {
        super("TimerA", CheckCategory.MOVEMENT);
        this.analyzer = analyzer;
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
        TimerAnalyzer.State state = data.computeCheckState(STATE_KEY, TimerAnalyzer.State::new);
        double score = analyzer.analyze(previous, current, state);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("timer score %.2f", score));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data) {
        evaluate(data);
    }
}
