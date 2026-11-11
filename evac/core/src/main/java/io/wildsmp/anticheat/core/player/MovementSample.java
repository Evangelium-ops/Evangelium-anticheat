package io.wildsmp.anticheat.core.player;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;

public record MovementSample(
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean onGround,
        long tickTimestamp,
        long nanoTime,
        TerrainKind terrain,
        MovementEffects effects,
        double estimatedTickLag) {
}
