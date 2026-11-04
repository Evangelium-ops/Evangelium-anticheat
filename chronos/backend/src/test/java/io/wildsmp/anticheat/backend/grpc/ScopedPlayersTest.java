package io.wildsmp.anticheat.backend.grpc;
import io.wildsmp.anticheat.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class ScopedPlayersTest {
 @Test void sameUUIDHasIndependentTenantStateAndStableHandoffIdentity(){
  String player=UUID.randomUUID().toString(),target=UUID.randomUUID().toString();
  ScopedPlayers a=new ScopedPlayers("launcher:ownerA"),b=new ScopedPlayers("launcher:ownerB"),handoff=new ScopedPlayers("launcher:ownerA");
  var join=ClientMessage.newBuilder().setJoin(PlayerJoin.newBuilder().setPlayerId(player)).build();
  String scoped=a.inbound(join).getJoin().getPlayerId();
  assertNotEquals(scoped,b.inbound(join).getJoin().getPlayerId());assertEquals(scoped,handoff.inbound(join).getJoin().getPlayerId());
  var attack=a.inbound(ClientMessage.newBuilder().setAttack(AttackPacket.newBuilder().setAttackerId(player).setTargetId(target)).build()).getAttack();
  assertEquals(scoped,attack.getAttackerId());assertEquals(a.id(target),attack.getTargetId());assertNotEquals(b.id(target),attack.getTargetId());
  ServerMessage verdict=ServerMessage.newBuilder().setVerdict(Verdict.newBuilder().setPlayerId(scoped)).build();
  assertEquals(player,a.outbound(verdict).getVerdict().getPlayerId());assertNull(b.outbound(verdict));
 }
}
