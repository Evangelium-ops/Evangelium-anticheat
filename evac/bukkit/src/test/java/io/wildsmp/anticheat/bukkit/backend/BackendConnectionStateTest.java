package io.wildsmp.anticheat.bukkit.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendConnectionStateTest {

    @Test
    void startsConnected() {
        BackendConnectionState state = new BackendConnectionState(5_000L);

        assertEquals(BackendConnectionState.Status.CONNECTED, state.status());
        assertFalse(state.isFailedClosed(0L));
    }

    @Test
    void withinTheGracePeriodAfterDisconnectStaysOpen() {
        BackendConnectionState state = new BackendConnectionState(5_000L);

        state.onDisconnected(1_000L);

        assertEquals(BackendConnectionState.Status.GRACE_PERIOD, state.status());
        assertFalse(state.isFailedClosed(1_000L + 4_000L)); // 4s into a 5s grace period
    }

    @Test
    void pastTheGracePeriodFailsClosed() {
        BackendConnectionState state = new BackendConnectionState(5_000L);

        state.onDisconnected(1_000L);

        assertTrue(state.isFailedClosed(1_000L + 5_001L));
        assertEquals(BackendConnectionState.Status.FAILED_CLOSED, state.status());
    }

    @Test
    void reconnectingDuringTheGracePeriodClearsFailedClosed() {
        BackendConnectionState state = new BackendConnectionState(5_000L);
        state.onDisconnected(1_000L);

        state.onConnected();

        assertEquals(BackendConnectionState.Status.CONNECTED, state.status());
        assertFalse(state.isFailedClosed(1_000L + 10_000L));
    }
}
