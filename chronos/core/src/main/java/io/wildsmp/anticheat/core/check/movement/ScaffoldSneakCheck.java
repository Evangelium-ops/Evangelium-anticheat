package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.BlockPlacePacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

public final class ScaffoldSneakCheck extends Check {

    private static final String STATE_KEY = "ScaffoldSneakCheck.State";
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;

    private final ScaffoldSneakAnalyzer analyzer;

    public ScaffoldSneakCheck() {
        this(new ScaffoldSneakAnalyzer());
    }

    public ScaffoldSneakCheck(ScaffoldSneakAnalyzer analyzer) {
        super("ScaffoldSneakA", CheckCategory.MOVEMENT);
        this.analyzer = analyzer;
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof BlockPlacePacket bpp)) {
            return;
        }
        evaluate(data, bpp.blockX(), bpp.blockY(), bpp.blockZ());
    }

    private void evaluate(PlayerData data, int blockX, int blockY, int blockZ) {
        MovementSample last = data.movementHistory().peekLast();
        if (last == null) {
            return;
        }
        ScaffoldSneakAnalyzer.State state = data.computeCheckState(STATE_KEY, ScaffoldSneakAnalyzer.State::new);
        double score = analyzer.analyze(last, blockX, blockY, blockZ, state);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("scaffold-sneak score %.2f", score));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data, int blockX, int blockY, int blockZ) {
        evaluate(data, blockX, blockY, blockZ);
    }
}
