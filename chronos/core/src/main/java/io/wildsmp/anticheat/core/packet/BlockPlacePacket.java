package io.wildsmp.anticheat.core.packet;

import java.util.UUID;

/**
 * A real Bukkit BlockPlaceEvent, not a client-reported packet - carries only the raw fact. No
 * geometry is computed bukkit-side; {@code ScaffoldAnalyzer} independently re-derives whether the
 * placement was actually within the player's real look direction/reach using the placer's own last
 * known {@link io.wildsmp.anticheat.core.player.MovementSample}.
 */
public record BlockPlacePacket(UUID playerId, int blockX, int blockY, int blockZ, long tick) implements InboundPacket {
}
