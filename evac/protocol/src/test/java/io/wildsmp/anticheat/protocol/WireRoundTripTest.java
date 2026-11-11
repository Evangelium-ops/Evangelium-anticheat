package io.wildsmp.anticheat.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WireRoundTripTest {

    @Test
    void movementPacketSurvivesEncodeDecode() throws IOException {
        ClientMessage original = ClientMessage.newBuilder()
                .setMovement(MovementPacket.newBuilder()
                        .setPlayerId("11111111-1111-1111-1111-111111111111")
                        .setX(1.5).setY(64.0).setZ(-2.25)
                        .setYaw(90.0f).setPitch(0.0f)
                        .setOnGround(true)
                        .setTick(42L)
                        .build())
                .build();

        ClientMessage decoded = ClientMessage.parseFrom(original.toByteArray());

        assertEquals(ClientMessage.PayloadCase.MOVEMENT, decoded.getPayloadCase());
        assertEquals(1.5, decoded.getMovement().getX(), 0.0001);
        assertEquals(42L, decoded.getMovement().getTick());
    }

    @Test
    void verdictSurvivesEncodeDecode() throws IOException {
        ServerMessage original = ServerMessage.newBuilder()
                .setVerdict(Verdict.newBuilder()
                        .setPlayerId("22222222-2222-2222-2222-222222222222")
                        .setPlayerName("Steve")
                        .setCheckName("ReachA")
                        .setCategory("COMBAT")
                        .setVl(12.5)
                        .setThreshold(10.0)
                        .setDetails("reach 3.40 blocks")
                        .setTimestampMillis(1_000_000L)
                        .build())
                .build();

        ServerMessage decoded = ServerMessage.parseFrom(original.toByteArray());

        assertEquals(ServerMessage.PayloadCase.VERDICT, decoded.getPayloadCase());
        assertEquals("ReachA", decoded.getVerdict().getCheckName());
        assertEquals(12.5, decoded.getVerdict().getVl(), 0.0001);
    }
}
