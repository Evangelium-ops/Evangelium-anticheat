package io.wildsmp.anticheat.backend.grpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.knowledge.evangelium.*;
import io.wildsmp.anticheat.backend.license.*;
import io.wildsmp.anticheat.backend.player.PlayerRegistry;
import io.wildsmp.anticheat.backend.plugin.PluginJarService;
import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.check.combat.*;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.PacketEventBus;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import io.wildsmp.anticheat.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LauncherAnticheatServiceTest {
 static class Output implements StreamObserver<ServerMessage> {
  final List<ServerMessage> messages=new ArrayList<>();Throwable error;
  public void onNext(ServerMessage m){messages.add(m);} public void onError(Throwable t){error=t;}public void onCompleted(){}
 }
 @Test void oneLauncherLicenseEnablesRemoteCombatAndReplayIsDenied(@TempDir Path dir) throws Exception {
  KeyPair authority=KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),identity=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  long now=System.currentTimeMillis();String pub=Base64.getEncoder().encodeToString(identity.getPublic().getEncoded());
  Reply license=new Reply(Map.of("status","ok","issued",""+now,"server",Hash.sha256(identity.getPublic().getEncoded()).substring(0,32))).sign(authority.getPrivate());
  String proof=ACLicenseProof.hello(ACLicenseProof.delegate(license,identity.getPrivate(),pub,now),now);
  AnticheatConfig config=AnticheatConfig.withDefaults(new AnticheatConfig.CheckSettings(true,1,0));
  ViolationManager violations=new ViolationManager(config);CheckRegistry checks=new CheckRegistry(violations);PlayerRegistry players=new PlayerRegistry();
  checks.register(new PositionHistoryCheck());checks.register(new ReachCheck(players::get));
  Files.writeString(dir.resolve("backend-private.pem"),"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(authority.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
  try(LicenseStore licenses=new LicenseStore(dir.resolve("legacy.db"))) {
   AnticheatServiceImpl engine=new AnticheatServiceImpl(new PacketEventBus(checks,(name,error)->{throw new AssertionError(error);}),violations,players,new PluginJarService(dir.resolve("absent.jar")),licenses,new Ed25519KeyManager(dir.resolve("backend-private.pem")));
   LauncherAnticheatService service=new LauncherAnticheatService(engine,authority.getPublic(),false);
   Output noLicense=new Output();var unauthorized=service.stream(noLicense);String attacker=UUID.randomUUID().toString(),target=UUID.randomUUID().toString();
   unauthorized.onNext(ClientMessage.newBuilder().setJoin(PlayerJoin.newBuilder().setPlayerId(attacker).setPlayerName("Steve")).build());
   assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(noLicense.error).getCode());assertNull(players.get(UUID.fromString(attacker)));
   Output output=new Output();var input=service.stream(output);
   input.onNext(ClientMessage.newBuilder().setHello(Hello.newBuilder().setLauncherProof(proof)).build());
   assertTrue(output.messages.getFirst().hasLauncherApproval());assertNull(output.error);
   input.onNext(ClientMessage.newBuilder().setJoin(PlayerJoin.newBuilder().setPlayerId(attacker).setPlayerName("Steve")).build());
   input.onNext(ClientMessage.newBuilder().setJoin(PlayerJoin.newBuilder().setPlayerId(target).setPlayerName("Alex")).build());
   input.onNext(ClientMessage.newBuilder().setMovement(MovementPacket.newBuilder().setPlayerId(target).setX(6).setY(64).setOnGround(true).setTick(10)).build());
   input.onNext(ClientMessage.newBuilder().setMovement(MovementPacket.newBuilder().setPlayerId(attacker).setY(64).setOnGround(true).setTick(10)).build());
   // A second licensed owner cannot overwrite this customer's players despite using the same UUIDs.
   var otherIdentity=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
   String otherPub=Base64.getEncoder().encodeToString(otherIdentity.getPublic().getEncoded());
   Reply otherLicense=new Reply(Map.of("status","ok","issued",""+now,"server",Hash.sha256(otherIdentity.getPublic().getEncoded()).substring(0,32))).sign(authority.getPrivate());
   String otherProof=ACLicenseProof.hello(ACLicenseProof.delegate(otherLicense,otherIdentity.getPrivate(),otherPub,now),now);
   Output otherOutput=new Output();var other=service.stream(otherOutput);
   other.onNext(ClientMessage.newBuilder().setHello(Hello.newBuilder().setLauncherProof(otherProof)).build());
   other.onNext(ClientMessage.newBuilder().setJoin(PlayerJoin.newBuilder().setPlayerId(attacker).setPlayerName("OtherSteve")).build());
   other.onNext(ClientMessage.newBuilder().setJoin(PlayerJoin.newBuilder().setPlayerId(target).setPlayerName("OtherAlex")).build());
   other.onNext(ClientMessage.newBuilder().setMovement(MovementPacket.newBuilder().setPlayerId(target).setX(1).setY(64).setOnGround(true).setTick(10)).build());
   other.onNext(ClientMessage.newBuilder().setMovement(MovementPacket.newBuilder().setPlayerId(attacker).setY(64).setOnGround(true).setTick(10)).build());
   input.onNext(ClientMessage.newBuilder().setAttack(AttackPacket.newBuilder().setAttackerId(attacker).setTargetId(target).setPerceivedTick(10)).build());
   other.onNext(ClientMessage.newBuilder().setAttack(AttackPacket.newBuilder().setAttackerId(attacker).setTargetId(target).setPerceivedTick(10)).build());
   assertFalse(otherOutput.messages.stream().anyMatch(ServerMessage::hasVerdict));
   assertTrue(output.messages.stream().anyMatch(m->m.hasVerdict()&&m.getVerdict().getCategory().equals("COMBAT")));
   assertEquals(attacker,output.messages.stream().filter(ServerMessage::hasVerdict).findFirst().orElseThrow().getVerdict().getPlayerId());
   assertFalse(output.messages.stream().anyMatch(ServerMessage::hasAssigned));
   Output mixed=new Output();var mixedInput=new LauncherAnticheatService(engine,authority.getPublic(),true).stream(mixed);
   mixedInput.onNext(ClientMessage.newBuilder().setHello(Hello.newBuilder().setServerName("pending legacy")).build());
   String mixedProof=ACLicenseProof.hello(ACLicenseProof.delegate(license,identity.getPrivate(),pub,now),now);
   mixedInput.onNext(ClientMessage.newBuilder().setHello(Hello.newBuilder().setLauncherProof(mixedProof)).build());
   assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(mixed.error).getCode(),"Cannot carry a legacy namespace into launcher authentication");
   Output replay=new Output();service.stream(replay).onNext(ClientMessage.newBuilder().setHello(Hello.newBuilder().setLauncherProof(proof)).build());
   assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(replay.error).getCode());
  }
 }
}
