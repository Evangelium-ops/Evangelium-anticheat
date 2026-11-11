package io.wildsmp.anticheat.core.packet;

import java.util.UUID;

/**
 * @param targetId the attacked player's UUID - already resolved from whatever local entity-id
 *                  scheme the originating server uses, since entity IDs are not comparable across
 *                  different Minecraft servers connected to the same backend
 * @param perceivedTick the attacker's tick-sync-adjusted view of "now", already computed by the
 *                       originating server (see {@link io.wildsmp.anticheat.core.check.combat.TickSyncTracker});
 *                       used by {@code ReachCheck} to backtrack the target's position
 */
public record AttackPacket(UUID targetId, long perceivedTick) implements InboundPacket {
}
