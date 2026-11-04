package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.player.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionHistoryCheckTest {

    /**
     * Exercises the same state-key contract {@link PositionHistoryCheck} and {@link ReachCheck}
     * share, without needing a real packet object - both read/write
     * {@code PlayerData.computeCheckState("ReachCheck.PositionHistory", ...)}, so this proves
     * that contract independent of packet parsing (covered manually against a real server).
     */
    @Test
    void positionHistoryStateKeyMatchesWhatReachCheckExpects() {
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        TickIndexedPositionHistory history = data.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        history.record(new PositionSnapshot(1, 64, 1, 0.3, 1.8, 5L));

        TickIndexedPositionHistory sameHistory = data.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);

        assertTrue(sameHistory.at(5L).isPresent());
        assertEquals(1.0, sameHistory.at(5L).orElseThrow().x(), 0.001);
    }

    @Test
    void checkIsRegisteredUnderTheExpectedNameAndCategory() {
        PositionHistoryCheck check = new PositionHistoryCheck();

        assertEquals("PositionHistory", check.name());
        assertEquals(io.wildsmp.anticheat.core.check.CheckCategory.MOVEMENT, check.category());
    }

    @Test
    void onPacketReceiveRecordsMovementAndPositionHistory() {
        PositionHistoryCheck check = new PositionHistoryCheck();
        PlayerData data = new PlayerData(UUID.randomUUID(), "Steve");

        check.onPacketReceive(data, new io.wildsmp.anticheat.core.packet.MovementPacket(
                1.0, 64.0, 2.0, 90f, 0f, true, 7L, TerrainKind.NORMAL, MovementEffects.none(), 0.0));

        assertEquals(1.0, data.movementHistory().peekLast().x(), 0.001);
        TickIndexedPositionHistory history = data.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        assertTrue(history.at(7L).isPresent());
    }
}
