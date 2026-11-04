package io.wildsmp.anticheat.core.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementEffectsTest {

    @Test
    void noneIsAllZeroValuesAndNoActiveStates() {
        MovementEffects none = MovementEffects.none();

        assertEquals(0, none.speedLevel());
        assertEquals(0, none.slownessLevel());
        assertEquals(0, none.jumpBoostLevel());
        assertEquals(false, none.levitation());
        assertEquals(false, none.slowFalling());
        assertEquals(false, none.sprinting());
        assertEquals(false, none.sneaking());
        assertEquals(false, none.swimming());
        assertEquals(false, none.gliding());
    }
}
