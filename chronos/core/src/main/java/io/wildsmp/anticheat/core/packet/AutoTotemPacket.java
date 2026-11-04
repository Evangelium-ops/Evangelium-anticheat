package io.wildsmp.anticheat.core.packet;

import java.util.UUID;

/**
 * Unlike every other {@link InboundPacket}, this doesn't come from a client-reported network
 * packet at all - it's forwarded from real Bukkit inventory/resurrect events. Bukkit-side already
 * confirmed the offhand totem had no legitimate cause (no real {@code InventoryClickEvent} or
 * {@code PlayerSwapHandItemsEvent} behind it - see {@code AutoTotemListener}) before this is ever
 * sent, so this packet's mere existence IS the violation; there's no threshold left to apply
 * backend-side, unlike {@code MaceAnalyzer}, which can independently re-derive fall distance from
 * movement packets as a cross-check. Inventory contents simply aren't otherwise observable at all.
 *
 * @param playerId the player this happened to
 * @param tick     the originating server's tick counter at the moment it was detected
 */
public record AutoTotemPacket(UUID playerId, long tick) implements InboundPacket {
}
