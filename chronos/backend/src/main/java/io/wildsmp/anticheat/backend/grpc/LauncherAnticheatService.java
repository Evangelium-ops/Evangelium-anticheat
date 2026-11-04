package io.wildsmp.anticheat.backend.grpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.knowledge.evangelium.ACLicenseProof;
import io.wildsmp.anticheat.protocol.*;
import java.security.PublicKey;

/** Authenticated AC streams share the existing remote engine, without a second server approval code. */
public final class LauncherAnticheatService extends AnticheatServiceGrpc.AnticheatServiceImplBase {
 private final AnticheatServiceImpl engine;
 private final ACLicenseProof.Verifier verifier;
 private final boolean allowLegacy;
 public LauncherAnticheatService(AnticheatServiceImpl engine, PublicKey trustedKeyServer, boolean allowLegacy) {
  this.engine=engine;this.verifier=new ACLicenseProof.Verifier(trustedKeyServer);this.allowLegacy=allowLegacy;
 }
 @Override public void checkForUpdate(UpdateCheckRequest request, StreamObserver<UpdateCheckResponse> output) { engine.checkForUpdate(request,output); }
 @Override public void downloadPluginJar(JarDownloadRequest request, StreamObserver<JarChunk> output) { engine.downloadPluginJar(request,output); }
 @Override public StreamObserver<ClientMessage> stream(StreamObserver<ServerMessage> output) {
  return new StreamObserver<>() {
   private volatile ACLicenseProof.Authorization authorization;
   private volatile boolean legacyApproved, legacy, closed;
   private volatile ScopedPlayers players;
   private final StreamObserver<ClientMessage> downstream=engine.stream(new StreamObserver<>() {
    public void onNext(ServerMessage message) {
     if(legacy) {
      if(message.hasSigned()) legacyApproved="approved".equals(message.getSigned().getType());
      emit(players==null?message:players.outbound(message));return;
     }
     if(message.hasCorrectPosition() || message.hasVerdict() && !message.getVerdict().getCategory().equals("COMBAT")) return;
     if(authorization!=null && authorization.expires()>System.currentTimeMillis()) emit(players.outbound(message));
    }
    public void onError(Throwable t){synchronized(output){closed=true;output.onError(t);}}
    public void onCompleted(){synchronized(output){closed=true;output.onCompleted();}}
   });
   public void onNext(ClientMessage message) {
    if(closed)return;
    if(message.hasHello()) {
     if(message.getHello().getLauncherProof().isEmpty()) {
      if(allowLegacy && authorization==null){
       String scope="legacy:"+message.getHello().getCode();
       if(legacyApproved){deny();return;}
       legacy=true;players=new ScopedPlayers(scope);downstream.onNext(message);return;
      }
      deny();return;
     }
     var next=verifier.verify(message.getHello().getLauncherProof(),System.currentTimeMillis());
     if(next==null || legacy || authorization!=null && !authorization.identity().equals(next.identity())){deny();return;}
     legacy=false;authorization=next;
     if(players==null)players=new ScopedPlayers("launcher:"+next.identity());
     emit(ServerMessage.newBuilder().setLauncherApproval(LauncherApproval.newBuilder()
             .setInstance(next.instance()).setExpiresMillis(next.expires())).build());return;
    }
    if(legacy ? !legacyApproved : authorization==null || authorization.expires()<=System.currentTimeMillis()){deny();return;}
    try { downstream.onNext(players.inbound(message));players.after(message); }
    catch(IllegalArgumentException invalid){deny();}
   }
   private void emit(ServerMessage message){synchronized(output){if(!closed && message!=null)output.onNext(message);}}
   private void deny(){closed=true;downstream.onError(Status.UNAUTHENTICATED.asRuntimeException());output.onError(Status.UNAUTHENTICATED.withDescription("AC launcher license verification failed").asRuntimeException());}
   public void onError(Throwable t){if(!closed){closed=true;downstream.onError(t);}}
   public void onCompleted(){if(!closed)downstream.onCompleted();}
  };
 }
}
