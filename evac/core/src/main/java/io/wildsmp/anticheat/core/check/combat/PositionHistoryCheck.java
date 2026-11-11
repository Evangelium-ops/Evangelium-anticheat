package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.packet.MovementPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

/**
 * Bookkeeping-only check: parses every incoming {@link MovementPacket} into a {@link
 * MovementSample} (feeding {@link PlayerData#recordMovement}), and separately records the same
 * position into that player's {@link TickIndexedPositionHistory}, tagged with the packet's tick -
 * populated for every player regardless of whether they're currently fighting anyone, so {@link
 * ReachCheck} always has a target's recent positions available to backtrack against. Never calls
 * flag() - this check exists purely to populate history other checks read from.
 */
public final class PositionHistoryCheck extends Check {

    static final String HISTORY_STATE_KEY = "ReachCheck.PositionHistory";
    private static final double DEFAULT_HALF_WIDTH = 0.3;
    private static final double DEFAULT_HEIGHT = 1.8;

    public PositionHistoryCheck() {
        super("PositionHistory", CheckCategory.MOVEMENT);
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof MovementPacket mp)) {
            return;
        }
        MovementSample sample = new MovementSample(
                mp.x(), mp.y(), mp.z(), mp.yaw(), mp.pitch(), mp.onGround(), mp.tick(), System.nanoTime(),
                mp.terrain(), mp.effects(), mp.estimatedTickLag());
        data.recordMovement(sample);

        TickIndexedPositionHistory history = data.computeCheckState(HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        history.record(new PositionSnapshot(sample.x(), sample.y(), sample.z(), DEFAULT_HALF_WIDTH, DEFAULT_HEIGHT, mp.tick()));
    }
}
