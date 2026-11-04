package io.wildsmp.anticheat.core.violation;

import io.wildsmp.anticheat.core.check.CheckCategory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViolationCodecTest {

    @Test
    void encodeThenDecodeProducesAnIdenticalViolation() {
        Violation original = new Violation(
                UUID.randomUUID(),
                "Steve",
                "KillauraA",
                CheckCategory.COMBAT,
                12.5,
                10.0,
                "angle delta 47.3",
                1_757_800_000_000L);

        byte[] encoded = ViolationCodec.encode(original);
        Violation decoded = ViolationCodec.decode(encoded);

        assertEquals(original, decoded);
    }

    @Test
    void handlesEmptyDetailsString() {
        Violation original = new Violation(
                UUID.randomUUID(), "Alex", "SpeedA", CheckCategory.MOVEMENT, 5.0, 5.0, "", 1L);

        Violation decoded = ViolationCodec.decode(ViolationCodec.encode(original));

        assertEquals("", decoded.details());
    }
}
