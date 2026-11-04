package io.wildsmp.anticheat.core.packet;

import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;

/** @param tick the originating Minecraft server's tick counter at the moment this was captured */
public record MovementPacket(
        double x, double y, double z, float yaw, float pitch, boolean onGround, long tick,
        TerrainKind terrain, MovementEffects effects, double estimatedTickLag)
        implements InboundPacket {
}
