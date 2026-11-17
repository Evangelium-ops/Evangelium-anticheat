package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlagBurstTest {
    private final FlagBurst<String> burst = new FlagBurst<>(3, 10_000);

    private int flag(String key, long now) {
        burst.note(key, now);
        return burst.count(key, now);
    }

    @Test
    void theThirdFlagInTenSecondsCompletesTheBurst() {
        assertEquals(0, flag("reach", 1_000));
        assertEquals(0, flag("reach", 4_000));
        assertEquals(1, flag("reach", 9_000));
        assertEquals(2, flag("reach", 9_500));   // still in it: counts on
    }

    @Test
    void threeFlagsSpreadOverMoreThanTenSecondsAreNotABurst() {
        assertEquals(0, flag("reach", 1_000));
        assertEquals(0, flag("reach", 6_000));
        assertEquals(0, flag("reach", 12_000));   // the first one is 11 s old
        assertEquals(1, flag("reach", 14_000));   // 6 s, 12 s, 14 s: three within ten seconds
    }

    @Test
    void aBurstEndsAndTheNextOneStartsFromOne() {
        flag("reach", 1_000); flag("reach", 2_000);
        assertEquals(1, flag("reach", 3_000));
        assertEquals(0, flag("reach", 60_000));
        assertEquals(0, flag("reach", 61_000));
        assertEquals(1, flag("reach", 62_000));
    }

    @Test
    void checksAreCountedApart() {
        flag("reach", 1_000); flag("reach", 2_000);
        assertEquals(0, flag("speed", 2_500));
        assertEquals(1, flag("reach", 3_000));
        assertEquals(0, burst.count("timer", 3_000));   // never flagged
    }
}
