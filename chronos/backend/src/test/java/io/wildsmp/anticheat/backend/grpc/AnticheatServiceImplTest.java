package io.wildsmp.anticheat.backend.grpc;

import io.grpc.stub.StreamObserver;
import io.wildsmp.anticheat.backend.license.Ed25519KeyManager;
import io.wildsmp.anticheat.backend.license.LicenseStore;
import io.wildsmp.anticheat.backend.player.PlayerRegistry;
import io.wildsmp.anticheat.backend.plugin.PluginJarService;
import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.check.combat.PositionHistoryCheck;
import io.wildsmp.anticheat.core.check.combat.ReachCheck;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.PacketEventBus;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import io.wildsmp.anticheat.protocol.AttackPacket;
import io.wildsmp.anticheat.protocol.ClientMessage;
import io.wildsmp.anticheat.protocol.Hello;
import io.wildsmp.anticheat.protocol.MovementPacket;
import io.wildsmp.anticheat.protocol.PlayerJoin;
import io.wildsmp.anticheat.protocol.ServerMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnticheatServiceImplTest {

    private static final class RecordingObserver implements StreamObserver<ServerMessage> {
        final List<ServerMessage> received = new ArrayList<>();

        @Override
        public void onNext(ServerMessage value) {
            received.add(value);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    private record TestBackend(AnticheatServiceImpl service, LicenseStore licenseStore) {
    }

    private static TestBackend newService() throws Exception {
        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 1.0, 0.0)); // low threshold - any real flag crosses it
        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry checkRegistry = new CheckRegistry(violationManager);
        PlayerRegistry playerRegistry = new PlayerRegistry();
        checkRegistry.register(new PositionHistoryCheck());
        checkRegistry.register(new ReachCheck(playerRegistry::get));
        PacketEventBus packetEventBus = new PacketEventBus(checkRegistry, (name, t) -> {
        });
        PluginJarService pluginJarService = new PluginJarService(java.nio.file.Path.of("does-not-exist.jar"));
        LicenseStore licenseStore = new LicenseStore(Files.createTempFile("license-test", ".db"));
        Path pemFile = Files.createTempFile("ed25519-test", ".pem");
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(pemFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n", StandardCharsets.UTF_8);
        Ed25519KeyManager keyManager = new Ed25519KeyManager(pemFile);
        return new TestBackend(
                new AnticheatServiceImpl(packetEventBus, violationManager, playerRegistry, pluginJarService, licenseStore, keyManager),
                licenseStore);
    }

    @Test
    void aBeyondReachAttackProducesAVerdictRoutedBackToTheSameConnection() throws Exception {
        AnticheatServiceImpl service = newService().service();
        RecordingObserver observer = new RecordingObserver();
        StreamObserver<ClientMessage> inbound = service.stream(observer);

        UUID attackerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        inbound.onNext(ClientMessage.newBuilder().setJoin(
                PlayerJoin.newBuilder().setPlayerId(attackerId.toString()).setPlayerName("Steve")).build());
        inbound.onNext(ClientMessage.newBuilder().setJoin(
                PlayerJoin.newBuilder().setPlayerId(targetId.toString()).setPlayerName("Alex")).build());

        // Target stands far away; PositionHistoryCheck records it via a movement packet.
        inbound.onNext(ClientMessage.newBuilder().setMovement(
                MovementPacket.newBuilder().setPlayerId(targetId.toString())
                        .setX(6.0).setY(64.0).setZ(0.0).setOnGround(true).setTick(10L)).build());
        // Attacker attacks from the origin - well beyond 3-block vanilla reach.
        inbound.onNext(ClientMessage.newBuilder().setMovement(
                MovementPacket.newBuilder().setPlayerId(attackerId.toString())
                        .setX(0.0).setY(64.0).setZ(0.0).setOnGround(true).setTick(10L)).build());
        inbound.onNext(ClientMessage.newBuilder().setAttack(
                AttackPacket.newBuilder().setAttackerId(attackerId.toString())
                        .setTargetId(targetId.toString()).setPerceivedTick(10L)).build());

        assertEquals(1, observer.received.size());
        assertEquals("ReachA", observer.received.get(0).getVerdict().getCheckName());
        assertTrue(observer.received.get(0).getVerdict().getVl() > 0.0);
    }

    @Test
    void reconnectingAnAlreadyApprovedServerGetsAFreshSeqNotARepeat() throws Exception {
        TestBackend backend = newService();
        RecordingObserver firstConnection = new RecordingObserver();
        StreamObserver<ClientMessage> inbound = backend.service().stream(firstConnection);

        // First contact - no code yet, gets assigned one and sits pending.
        inbound.onNext(ClientMessage.newBuilder().setHello(
                Hello.newBuilder().setServerName("test-server").setMcVersion("1.21.4").setInstanceId("instance-1")).build());
        String code = firstConnection.received.get(0).getAssigned().getCode();

        // An operator approves it from the panel - bumps seq and pushes the first signed frame,
        // exactly like AnticheatServiceImpl's REST routes do.
        backend.licenseStore().updateState(code, LicenseStore.State.APPROVED);
        backend.service().pushSignedFrame(code, "approved", firstConnection);
        long firstSeq = firstConnection.received.get(1).getSigned().getSeq();

        // The server reconnects (a real network blip, or the auto-reconnect this session added) -
        // same instanceId, sends Hello again with its now-known code, same as a fresh connect would.
        RecordingObserver secondConnection = new RecordingObserver();
        StreamObserver<ClientMessage> reconnectInbound = backend.service().stream(secondConnection);
        reconnectInbound.onNext(ClientMessage.newBuilder().setHello(
                Hello.newBuilder().setCode(code).setServerName("test-server")
                        .setMcVersion("1.21.4").setInstanceId("instance-1")).build());

        assertEquals(1, secondConnection.received.size());
        long reconnectSeq = secondConnection.received.get(0).getSigned().getSeq();
        assertTrue(reconnectSeq > firstSeq,
                "a reconnect's re-approval must get a fresh seq, not repeat the last one - "
                        + "a repeated seq looks like a replay to the client and gets rejected, "
                        + "permanently blocking re-authorization until the whole plugin restarts");
    }
}
