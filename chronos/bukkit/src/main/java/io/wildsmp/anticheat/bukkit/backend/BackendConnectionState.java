package io.wildsmp.anticheat.bukkit.backend;

/**
 * The chosen trade-off from the spec: a brief network blip must not punish players, but an
 * extended backend outage must not let cheaters play unmonitored either. {@code
 * isFailedClosed(now)} is what {@link CombatLockdownListener} checks before letting an attack
 * through.
 */
public final class BackendConnectionState {

    public enum Status {
        CONNECTED, GRACE_PERIOD, FAILED_CLOSED
    }

    private final long gracePeriodMillis;
    private volatile Status status = Status.CONNECTED;
    private volatile long disconnectedAtMillis = -1;

    public BackendConnectionState(long gracePeriodMillis) {
        this.gracePeriodMillis = gracePeriodMillis;
    }

    public synchronized void onDisconnected(long nowMillis) {
        if (status == Status.CONNECTED) {
            disconnectedAtMillis = nowMillis;
            status = Status.GRACE_PERIOD;
        }
    }

    public synchronized void onConnected() {
        status = Status.CONNECTED;
        disconnectedAtMillis = -1;
    }

    /** Call on a timer with the current time; transitions GRACE_PERIOD -> FAILED_CLOSED once the grace period elapses. */
    public synchronized boolean isFailedClosed(long nowMillis) {
        if (status == Status.GRACE_PERIOD && nowMillis - disconnectedAtMillis > gracePeriodMillis) {
            status = Status.FAILED_CLOSED;
        }
        return status == Status.FAILED_CLOSED;
    }

    public Status status() {
        return status;
    }
}
