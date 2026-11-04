package io.wildsmp.anticheat.core.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickSyncTrackerTest {

    @Test
    void withNoAcknowledgmentsYetLagIsZero() {
        TickSyncTracker tracker = new TickSyncTracker();

        assertEquals(0, tracker.estimatedTickLag());
        assertEquals(50, tracker.clientPerceivedTick(50));
    }

    @Test
    void singleAcknowledgmentSetsTheLagDirectly() {
        TickSyncTracker tracker = new TickSyncTracker();
        int id = tracker.sendTransaction(100L);

        tracker.onTransactionAcknowledged(id, 103L);

        assertEquals(3, tracker.estimatedTickLag());
        assertEquals(47, tracker.clientPerceivedTick(50));
    }

    @Test
    void unknownTransactionIdIsIgnoredWithoutError() {
        TickSyncTracker tracker = new TickSyncTracker();

        tracker.onTransactionAcknowledged(9999, 200L);

        assertEquals(0, tracker.estimatedTickLag());
    }

    @Test
    void repeatedAcknowledgmentsSmoothTowardTheNewValueRatherThanSnapping() {
        TickSyncTracker tracker = new TickSyncTracker();
        int first = tracker.sendTransaction(0L);
        tracker.onTransactionAcknowledged(first, 2L); // lag sample = 2

        int second = tracker.sendTransaction(10L);
        tracker.onTransactionAcknowledged(second, 20L); // lag sample = 10, a big jump

        long lagAfterTwoSamples = tracker.estimatedTickLag();
        assertTrue(lagAfterTwoSamples > 2 && lagAfterTwoSamples < 10,
                "expected smoothed value between the two samples, was " + lagAfterTwoSamples);
    }

    @Test
    void clientPerceivedTickNeverGoesNegative() {
        TickSyncTracker tracker = new TickSyncTracker();
        int id = tracker.sendTransaction(0L);
        tracker.onTransactionAcknowledged(id, 50L); // huge lag sample

        assertEquals(0, tracker.clientPerceivedTick(10L));
    }
}
