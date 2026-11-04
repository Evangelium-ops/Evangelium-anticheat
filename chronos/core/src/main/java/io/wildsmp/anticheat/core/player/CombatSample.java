package io.wildsmp.anticheat.core.player;

import java.util.UUID;

public record CombatSample(
        UUID targetId,
        float yaw,
        float pitch,
        double distance,
        long tickTimestamp,
        long nanoTime) {
}
