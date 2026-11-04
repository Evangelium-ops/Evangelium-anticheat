package io.wildsmp.anticheat.core.packet;

import java.util.UUID;

/**
 * A real Bukkit EntityDamageEvent(cause=FALL) outcome (or its confirmed ABSENCE despite a tracked
 * fall distance that should have produced damage) - not a client-reported packet, same reasoning
 * as {@link DamagePacket}. Only sent when bukkit's own fall-distance tracking says damage SHOULD
 * have applied.
 */
public record FallDamagePacket(UUID playerId, double trackedFallDistance, long tick) implements InboundPacket {
}
