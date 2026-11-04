package io.wildsmp.anticheat.core.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickClockTest {

    @Test
    void startsAtZero() {
        TickClock clock = new TickClock();

        assertEquals(0, clock.current());
    }

    @Test
    void advanceIncrementsAndReturnsTheNewValue() {
        TickClock clock = new TickClock();

        long first = clock.advance();
        long second = clock.advance();

        assertEquals(1, first);
        assertEquals(2, second);
        assertEquals(2, clock.current());
    }
}
