package io.wildsmp.anticheat.core.check.combat;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single, shared, monotonically increasing tick counter every combat-check component in this
 * package correlates its tick-indexed data against. The bukkit adapter advances this once per
 * server tick; everything else here only reads it.
 */
public final class TickClock {

    private final AtomicLong currentTick = new AtomicLong(0);

    public long current() {
        return currentTick.get();
    }

    public long advance() {
        return currentTick.incrementAndGet();
    }
}
