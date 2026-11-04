package io.wildsmp.anticheat.core.packet;

import java.util.UUID;

/**
 * Unlike every other {@link InboundPacket}, this doesn't come from a client-reported network
 * packet at all - it's forwarded from a real, server-authoritative Bukkit {@code
 * EntityDamageByEntityEvent}, so a hacked client can't lie about {@link #damage}. Bukkit-side only
 * sends this for damage causes a check actually cares about (currently: mace hits only), not every
 * hit landed.
 *
 * @param victimId the damaged player's UUID
 * @param damage   the real, final damage the server applied for this hit
 * @param tick     the originating server's tick counter at the moment the event fired
 */
public record DamagePacket(UUID victimId, double damage, long tick) implements InboundPacket {
}
