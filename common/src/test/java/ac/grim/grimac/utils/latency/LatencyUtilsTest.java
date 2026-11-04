package ac.grim.grimac.utils.latency;

import ac.grim.grimac.player.GrimPlayer;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LatencyUtilsTest {
    private GrimPlayer player() throws ReflectiveOperationException {
        GrimPlayer player = mock(GrimPlayer.class);
        var acknowledged = GrimPlayer.class.getField("lastTransactionReceived");
        acknowledged.setAccessible(true);
        acknowledged.set(player, new AtomicInteger());
        return player;
    }

    @Test void olderTransactionTasksCannotBeBlockedByAFutureTask() throws Exception {
        LatencyUtils utils = new LatencyUtils(player());
        List<Integer> applied = new ArrayList<>();
        utils.addRealTimeTask(10, () -> applied.add(10));
        utils.addRealTimeTask(5, () -> applied.add(5));
        utils.handleNettySyncTransaction(5);
        assertEquals(List.of(5), applied, "Apply acknowledged world state even if tasks arrived out of order");
        utils.handleNettySyncTransaction(10);
        assertEquals(List.of(5, 10), applied);
    }

    @Test void tasksApplyInTransactionOrderAndSameTransactionKeepsInsertionOrder() throws Exception {
        LatencyUtils utils = new LatencyUtils(player());
        List<String> applied = new ArrayList<>();
        utils.addRealTimeTask(10, () -> applied.add("ten"));
        utils.addRealTimeTask(5, () -> applied.add("five-first"));
        utils.addRealTimeTask(5, () -> applied.add("five-second"));
        utils.handleNettySyncTransaction(4);
        assertTrue(applied.isEmpty(), "Never apply updates before the client acknowledges them");
        utils.handleNettySyncTransaction(10);
        assertEquals(List.of("five-first", "five-second", "ten"), applied);
    }

    @Test void acknowledgementBetweenFastCheckAndQueueInsertionDoesNotStrandATask() throws Exception {
        GrimPlayer player = player();
        LatencyUtils utils = new LatencyUtils(player);
        AtomicInteger applied = new AtomicInteger();
        Thread writer = new Thread(() -> utils.addRealTimeTask(5, applied::incrementAndGet));
        synchronized (utils) {
            writer.start();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (writer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(Thread.State.BLOCKED, writer.getState());
            player.lastTransactionReceived.set(5);
            utils.handleNettySyncTransaction(5);
        }
        writer.join(2000);
        assertFalse(writer.isAlive());
        assertEquals(1, applied.get(), "A completed acknowledgement must be rechecked while holding the queue lock");
    }

    @Test void callbacksCanQueueFutureUpdatesWithoutApplyingThemEarly() throws Exception {
        LatencyUtils utils = new LatencyUtils(player());
        List<Integer> applied = new ArrayList<>();
        utils.addRealTimeTask(5, () -> {
            applied.add(5);
            utils.addRealTimeTask(6, () -> applied.add(6));
        });
        utils.handleNettySyncTransaction(5);
        assertEquals(List.of(5), applied);
        utils.handleNettySyncTransaction(6);
        assertEquals(List.of(5, 6), applied);
    }
}
