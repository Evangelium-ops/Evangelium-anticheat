package io.wildsmp.anticheat.core.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickIndexedPositionHistoryTest {

    @Test
    void recordedSnapshotIsRetrievableByItsTick() {
        TickIndexedPositionHistory history = new TickIndexedPositionHistory();
        PositionSnapshot snapshot = new PositionSnapshot(10.0, 64.0, 20.0, 0.3, 1.8, 100L);

        history.record(snapshot);

        assertEquals(snapshot, history.at(100L).orElseThrow());
    }

    @Test
    void tickOutsideTheWindowIsEvicted() {
        TickIndexedPositionHistory history = new TickIndexedPositionHistory();
        history.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, 0L));

        for (long tick = 1; tick <= TickIndexedPositionHistory.WINDOW_TICKS + 5; tick++) {
            history.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, tick));
        }

        assertTrue(history.at(0L).isEmpty());
    }

    @Test
    void aTeleportSizedJumpClearsStalePreJumpSnapshots() {
        TickIndexedPositionHistory history = new TickIndexedPositionHistory();
        history.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, 10L));
        history.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, 11L));

        // A /tp, respawn, warp, etc. - not physically reachable in one tick of normal movement.
        history.record(new PositionSnapshot(115, 64, 0, 0.3, 1.8, 12L));

        // A lookup that lands right on the old pre-teleport ticks must never resolve to the stale
        // (0,64,0) position anymore - either there's nothing left to find that far back, or the
        // only thing within tolerance is the post-teleport position, never the evicted one.
        assertTrue(history.at(10L).map(s -> s.x() != 0.0).orElse(true));
        assertTrue(history.at(11L).map(s -> s.x() != 0.0).orElse(true));
        assertEquals(115.0, history.at(12L).orElseThrow().x(), 0.001);
    }

    @Test
    void neverRecordedTickReturnsEmpty() {
        TickIndexedPositionHistory history = new TickIndexedPositionHistory();
        history.record(new PositionSnapshot(0, 64, 0, 0.3, 1.8, 5L));

        assertTrue(history.at(999L).isEmpty());
    }

    @Test
    void distanceToAPointOutsideTheBoxMeasuresToTheNearestFace() {
        // box centered at (0,0,0) to (0,1.8,0), half-width 0.3 -> spans x/z [-0.3,0.3], y [0,1.8]
        PositionSnapshot snapshot = new PositionSnapshot(0, 0, 0, 0.3, 1.8, 0L);

        double distance = snapshot.distanceTo(5.0, 0.5, 0.0);

        assertEquals(4.7, distance, 0.001); // 5.0 - 0.3 (box's +x face)
    }

    @Test
    void distanceToAPointInsideTheBoxIsZero() {
        PositionSnapshot snapshot = new PositionSnapshot(0, 0, 0, 0.3, 1.8, 0L);

        double distance = snapshot.distanceTo(0.0, 1.0, 0.0);

        assertEquals(0.0, distance, 0.001);
    }
}
