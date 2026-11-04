package io.wildsmp.anticheat.backend.grpc;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.knowledge.evangelium.Reply;
import io.wildsmp.anticheat.backend.license.*;
import io.wildsmp.anticheat.backend.player.PlayerRegistry;
import io.wildsmp.anticheat.backend.plugin.PluginJarService;
import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.check.combat.*;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.PacketEventBus;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import io.wildsmp.anticheat.protocol.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.net.InetSocketAddress;
import java.util.Base64;
/** Test harness only; never packaged in the backend application or AC. */
public final class LocalBackendFixture {
 public static void main(String[] args) throws Exception {
  Path dir=Path.of(args[0]);Files.createDirectories(dir);
  var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  Files.writeString(dir.resolve("fixture-key.pem"),"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
  var config=AnticheatConfig.withDefaults(new AnticheatConfig.CheckSettings(true,10,1));
  var violations=new ViolationManager(config);var registry=new CheckRegistry(violations);var players=new PlayerRegistry();
  registry.register(new PositionHistoryCheck());registry.register(new ReachCheck(players::get));registry.register(new KillauraCheck(players::get));
  var store=new LicenseStore(dir.resolve("fixture.db"));
  var engine=new AnticheatServiceImpl(new PacketEventBus(registry,(n,t)->t.printStackTrace()),violations,players,new PluginJarService(dir.resolve("absent.jar")),store,new Ed25519KeyManager(dir.resolve("fixture-key.pem")));
  java.security.PublicKey trusted;
  try(var input=LocalBackendFixture.class.getResourceAsStream("/evangelium-public.key")){trusted=Reply.publicKey(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
  var authenticated=new LauncherAnticheatService(engine,trusted,false);
  var logging=new AnticheatServiceGrpc.AnticheatServiceImplBase(){
   @Override public StreamObserver<ClientMessage> stream(StreamObserver<ServerMessage> output){
    var input=authenticated.stream(new StreamObserver<>(){
     public void onNext(ServerMessage message){if(message.hasLauncherApproval())System.out.println("AC_TEST_AUTHORIZED");output.onNext(message);}
     public void onError(Throwable t){System.out.println("AC_TEST_REJECTED "+io.grpc.Status.fromThrowable(t).getCode());output.onError(t);}
     public void onCompleted(){output.onCompleted();}
    });
    return new StreamObserver<>(){
     public void onNext(ClientMessage message){if(message.hasJoin())System.out.println("AC_TEST_JOIN "+message.getJoin().getPlayerName());input.onNext(message);}
     public void onError(Throwable t){input.onError(t);}public void onCompleted(){input.onCompleted();}
    };
   }
  };
  var server=NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",29555)).addService(logging).build().start();
  Runtime.getRuntime().addShutdownHook(new Thread(()->{server.shutdownNow();store.close();}));
  System.out.println("AC_TEST_BACKEND_READY");server.awaitTermination();
 }
}
