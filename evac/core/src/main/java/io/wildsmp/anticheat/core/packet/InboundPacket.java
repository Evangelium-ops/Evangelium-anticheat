package io.wildsmp.anticheat.core.packet;

/**
 * Everything a {@link io.wildsmp.anticheat.core.check.Check} can receive from {@link
 * PacketEventBus#dispatchPacketReceive}. Deliberately independent of PacketEvents (or any other
 * network/protocol library) so core stays a plain-JDK module: the platform adapter that actually
 * decodes a network packet (today, {@code backend}'s gRPC service, translating a {@code
 * protocol.ClientMessage}) is responsible for constructing one of these.
 */
public sealed interface InboundPacket permits MovementPacket, AttackPacket, DamagePacket, AutoTotemPacket, FallDamagePacket, BlockPlacePacket {
}
