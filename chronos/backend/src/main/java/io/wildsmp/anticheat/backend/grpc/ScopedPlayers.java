package io.wildsmp.anticheat.backend.grpc;
import io.knowledge.evangelium.Hash;
import io.wildsmp.anticheat.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Shared remote engine state is namespaced by the verified owner, never by a client-chosen server name. */
final class ScopedPlayers {
 private final String namespace;
 private final Map<String,String> originals=new ConcurrentHashMap<>();
 ScopedPlayers(String namespace){this.namespace=namespace;}
 String id(String original){
  String uuid=UUID.fromString(original).toString();
  String hash=Hash.sha256((namespace+":"+uuid).getBytes(StandardCharsets.UTF_8)).substring(0,32);
  return new UUID(Long.parseUnsignedLong(hash.substring(0,16),16),Long.parseUnsignedLong(hash.substring(16),16)).toString();
 }
 private String actor(String original){String scoped=id(original);if(!originals.containsKey(scoped)&&originals.size()>=4096)throw new IllegalArgumentException("Too many active AC players");originals.put(scoped,original);return scoped;}
 ClientMessage inbound(ClientMessage m){var b=m.toBuilder();switch(m.getPayloadCase()){
  case JOIN -> b.setJoin(m.getJoin().toBuilder().setPlayerId(actor(m.getJoin().getPlayerId())));
  case QUIT -> b.setQuit(m.getQuit().toBuilder().setPlayerId(id(m.getQuit().getPlayerId())));
  case MOVEMENT -> b.setMovement(m.getMovement().toBuilder().setPlayerId(actor(m.getMovement().getPlayerId())));
  case ATTACK -> b.setAttack(m.getAttack().toBuilder().setAttackerId(actor(m.getAttack().getAttackerId())).setTargetId(id(m.getAttack().getTargetId())));
  case DAMAGE -> b.setDamage(m.getDamage().toBuilder().setAttackerId(actor(m.getDamage().getAttackerId())).setVictimId(id(m.getDamage().getVictimId())));
  case AUTO_TOTEM -> b.setAutoTotem(m.getAutoTotem().toBuilder().setPlayerId(actor(m.getAutoTotem().getPlayerId())));
  case FALL_DAMAGE -> b.setFallDamage(m.getFallDamage().toBuilder().setPlayerId(actor(m.getFallDamage().getPlayerId())));
  case BLOCK_PLACE -> b.setBlockPlace(m.getBlockPlace().toBuilder().setPlayerId(actor(m.getBlockPlace().getPlayerId())));
  case HEARTBEAT -> {var heartbeat=m.getHeartbeat().toBuilder().clearOnlinePlayerIds();for(String player:m.getHeartbeat().getOnlinePlayerIdsList())heartbeat.addOnlinePlayerIds(actor(player));b.setHeartbeat(heartbeat);}
  default -> { }
 }return b.build();}
 void after(ClientMessage m){if(m.hasQuit())originals.remove(id(m.getQuit().getPlayerId()));}
 ServerMessage outbound(ServerMessage m){
  if(m.hasVerdict()){String player=originals.get(m.getVerdict().getPlayerId());return player==null?null:m.toBuilder().setVerdict(m.getVerdict().toBuilder().setPlayerId(player)).build();}
  if(m.hasCorrectPosition()){String player=originals.get(m.getCorrectPosition().getPlayerId());return player==null?null:m.toBuilder().setCorrectPosition(m.getCorrectPosition().toBuilder().setPlayerId(player)).build();}
  return m;
 }
}
