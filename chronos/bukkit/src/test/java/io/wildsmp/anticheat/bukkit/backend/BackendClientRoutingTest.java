package io.wildsmp.anticheat.bukkit.backend;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.wildsmp.anticheat.protocol.*;
import io.wildsmp.anticheat.bukkit.license.LicenseClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.*;
import static org.junit.jupiter.api.Assertions.*;

class BackendClientRoutingTest {
 @Test void realStreamRequiresApprovalAndRoutesOnlyCombat(@TempDir Path dir) throws Exception {
  KeyPair kp=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  LicenseClient license=new LicenseClient(dir.resolve("license.yml"),kp.getPublic());
  CompletableFuture<Void> finished=new CompletableFuture<>();AtomicInteger verdicts=new AtomicInteger();AtomicInteger corrections=new AtomicInteger();
  String player=UUID.randomUUID().toString();
  Server server=ServerBuilder.forPort(0).addService(new AnticheatServiceGrpc.AnticheatServiceImplBase(){
   @Override public StreamObserver<ClientMessage> stream(StreamObserver<ServerMessage> output) {
    return new StreamObserver<>() {
     public void onNext(ClientMessage message) {
      if (!message.hasHello()) return;
      try {
       Verdict combat=Verdict.newBuilder().setPlayerId(player).setPlayerName("JavaPlayer").setCheckName("ReachA").setCategory("COMBAT").build();
       output.onNext(ServerMessage.newBuilder().setVerdict(combat).build()); // not approved yet
       output.onNext(ServerMessage.newBuilder().setAssigned(Assigned.newBuilder().setCode("TEST-CODE")).build());
       long now=System.currentTimeMillis();String canonical="approved\nTEST-CODE\n1\n"+now;
       Signature signature=Signature.getInstance("Ed25519");signature.initSign(kp.getPrivate());signature.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
       output.onNext(ServerMessage.newBuilder().setSigned(SignedMessage.newBuilder().setType("approved").setCode("TEST-CODE").setSeq(1).setIssuedAtMillis(now).setSignature(com.google.protobuf.ByteString.copyFrom(signature.sign()))).build());
       output.onNext(ServerMessage.newBuilder().setVerdict(combat.toBuilder().setCategory("MOVEMENT")).build());
       output.onNext(ServerMessage.newBuilder().setVerdict(combat).build());
       output.onNext(ServerMessage.newBuilder().setCorrectPosition(CorrectPosition.newBuilder().setPlayerId(player).setX(100)).build());
       output.onNext(ServerMessage.newBuilder().setAssigned(Assigned.newBuilder().setCode("DONE")).build());
      } catch(Exception e) { output.onError(e); }
     }
     public void onError(Throwable error) { }
     public void onCompleted() { output.onCompleted(); }
    };
   }
  }).build().start();
  BackendClient client=new BackendClient("127.0.0.1",server.getPort(),false);
  try {
   client.addAssignedListener(code -> {if(code.equals("DONE")) finished.complete(null);else license.onAssigned(code);});
   client.addSignedListener(frame -> {if(license.acceptIfApproved(frame))client.setApproved(true);});
   client.addVerdictListener(v -> verdicts.incrementAndGet());client.addCorrectPositionListener(v -> corrections.incrementAndGet());
   client.connect();client.sendHello(license.buildHello("fixture","1.21.11"));finished.get(10,TimeUnit.SECONDS);
   assertTrue(client.isApproved());assertEquals(1,verdicts.get());assertEquals(0,corrections.get());
  } finally { client.shutdown();server.shutdownNow().awaitTermination(10,TimeUnit.SECONDS); }
 }
}
