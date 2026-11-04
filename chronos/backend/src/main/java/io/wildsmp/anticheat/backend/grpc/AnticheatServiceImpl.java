package io.wildsmp.anticheat.backend.grpc;

import io.grpc.stub.StreamObserver;
import io.wildsmp.anticheat.backend.license.Ed25519KeyManager;
import io.wildsmp.anticheat.backend.license.LicenseStore;
import io.wildsmp.anticheat.backend.player.PlayerRegistry;
import io.wildsmp.anticheat.backend.plugin.PluginJarService;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.AutoTotemPacket;
import io.wildsmp.anticheat.core.packet.BlockPlacePacket;
import io.wildsmp.anticheat.core.packet.DamagePacket;
import io.wildsmp.anticheat.core.packet.FallDamagePacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.packet.MovementPacket;
import io.wildsmp.anticheat.core.packet.PacketEventBus;
import io.wildsmp.anticheat.core.player.PlayerData;
import io.wildsmp.anticheat.core.violation.Violation;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import io.wildsmp.anticheat.protocol.AnticheatServiceGrpc;
import io.wildsmp.anticheat.protocol.ClientMessage;
import io.wildsmp.anticheat.protocol.JarChunk;
import io.wildsmp.anticheat.protocol.JarDownloadRequest;
import io.wildsmp.anticheat.protocol.ServerMessage;
import io.wildsmp.anticheat.protocol.UpdateCheckRequest;
import io.wildsmp.anticheat.protocol.UpdateCheckResponse;
import io.wildsmp.anticheat.protocol.Verdict;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gRPC endpoints the backend exposes: a bidirectional stream (one call per connected
 * Minecraft server, multiplexing every online player's packets both ways - routes each violation
 * back down whichever connection most recently sent activity for that player, see {@link
 * #sessionByPlayer}), plus the plugin auto-update check/download RPCs.
 */
public final class AnticheatServiceImpl extends AnticheatServiceGrpc.AnticheatServiceImplBase
        implements io.wildsmp.anticheat.core.check.movement.MovementCorrectionSink {

    private static final int DOWNLOAD_CHUNK_SIZE = 65536; // 64KB per chunk

    private final PacketEventBus packetEventBus;
    private final ViolationManager violationManager;
    private final PlayerRegistry playerRegistry;
    private final PluginJarService pluginJarService;
    private final LicenseStore licenseStore;
    private final Ed25519KeyManager keyManager;
    private final Map<UUID, StreamObserver<ServerMessage>> sessionByPlayer = new ConcurrentHashMap<>();
    private final Map<String, StreamObserver<ServerMessage>> sessionByCode = new ConcurrentHashMap<>();

    public AnticheatServiceImpl(PacketEventBus packetEventBus, ViolationManager violationManager,
                                 PlayerRegistry playerRegistry, PluginJarService pluginJarService,
                                 LicenseStore licenseStore, Ed25519KeyManager keyManager) {
        this.packetEventBus = packetEventBus;
        this.violationManager = violationManager;
        this.playerRegistry = playerRegistry;
        this.pluginJarService = pluginJarService;
        this.licenseStore = licenseStore;
        this.keyManager = keyManager;
        this.violationManager.addListener(this::onViolation);
    }

    @Override
    public StreamObserver<ClientMessage> stream(StreamObserver<ServerMessage> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ClientMessage message) {
                handle(message, responseObserver);
            }

            @Override
            public void onError(Throwable t) {
                sessionByPlayer.entrySet().removeIf(entry -> entry.getValue() == responseObserver);
                sessionByCode.entrySet().removeIf(entry -> entry.getValue() == responseObserver);
            }

            @Override
            public void onCompleted() {
                sessionByPlayer.entrySet().removeIf(entry -> entry.getValue() == responseObserver);
                sessionByCode.entrySet().removeIf(entry -> entry.getValue() == responseObserver);
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void checkForUpdate(UpdateCheckRequest request, StreamObserver<UpdateCheckResponse> responseObserver) {
        UpdateCheckResponse.Builder response = UpdateCheckResponse.newBuilder();
        if (pluginJarService.hasJar()) {
            response.setSha256(pluginJarService.sha256()).setSizeBytes(pluginJarService.sizeBytes());
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void downloadPluginJar(JarDownloadRequest request, StreamObserver<JarChunk> responseObserver) {
        if (!pluginJarService.hasJar()) {
            responseObserver.onCompleted();
            return;
        }
        byte[] bytes = pluginJarService.readAllBytes();
        for (int offset = 0; offset < bytes.length; offset += DOWNLOAD_CHUNK_SIZE) {
            int end = Math.min(offset + DOWNLOAD_CHUNK_SIZE, bytes.length);
            responseObserver.onNext(JarChunk.newBuilder()
                    .setData(com.google.protobuf.ByteString.copyFrom(bytes, offset, end - offset))
                    .build());
        }
        responseObserver.onCompleted();
    }

    private void handle(ClientMessage message, StreamObserver<ServerMessage> connection) {
        switch (message.getPayloadCase()) {
            case JOIN -> {
                UUID id = UUID.fromString(message.getJoin().getPlayerId());
                // getOrCreate only applies a name on first creation - if a movement/attack packet
                // already created this player's PlayerData with a placeholder name (e.g. this
                // join arriving after a backend restart, while they were already connected),
                // explicitly correct it now that the real name is known.
                PlayerData data = playerRegistry.getOrCreate(id, message.getJoin().getPlayerName());
                data.updateName(message.getJoin().getPlayerName());
                sessionByPlayer.put(id, connection);
            }
            case QUIT -> {
                UUID id = UUID.fromString(message.getQuit().getPlayerId());
                playerRegistry.markQuit(id);
                sessionByPlayer.remove(id, connection);
            }
            case MOVEMENT -> {
                var mp = message.getMovement();
                UUID id = UUID.fromString(mp.getPlayerId());
                PlayerData data = playerRegistry.getOrCreate(id);
                sessionByPlayer.put(id, connection);
                TerrainKind terrain = TerrainKind.values()[mp.getTerrainKind()];
                MovementEffects effects = new MovementEffects(mp.getSpeedLevel(), mp.getSlownessLevel(),
                        mp.getJumpBoostLevel(), mp.getLevitation(), mp.getSlowFalling(), mp.getSprinting(),
                        mp.getSneaking(), mp.getSwimming(), mp.getGliding(), mp.getFlightAllowed(),
                        mp.getRecentlyLaunched());
                InboundPacket packet = new MovementPacket(
                        mp.getX(), mp.getY(), mp.getZ(), mp.getYaw(), mp.getPitch(), mp.getOnGround(), mp.getTick(),
                        terrain, effects, mp.getEstimatedTickLag());
                packetEventBus.dispatchPacketReceive(data, packet);
            }
            case ATTACK -> {
                var ap = message.getAttack();
                UUID attackerId = UUID.fromString(ap.getAttackerId());
                PlayerData attackerData = playerRegistry.getOrCreate(attackerId);
                InboundPacket packet = new AttackPacket(UUID.fromString(ap.getTargetId()), ap.getPerceivedTick());
                packetEventBus.dispatchPacketReceive(attackerData, packet);
            }
            case DAMAGE -> {
                var dp = message.getDamage();
                UUID attackerId = UUID.fromString(dp.getAttackerId());
                PlayerData attackerData = playerRegistry.getOrCreate(attackerId);
                InboundPacket packet = new DamagePacket(UUID.fromString(dp.getVictimId()), dp.getDamage(), dp.getTick());
                packetEventBus.dispatchPacketReceive(attackerData, packet);
            }
            case AUTO_TOTEM -> {
                var atp = message.getAutoTotem();
                UUID playerId = UUID.fromString(atp.getPlayerId());
                PlayerData playerData = playerRegistry.getOrCreate(playerId);
                InboundPacket packet = new AutoTotemPacket(playerId, atp.getTick());
                packetEventBus.dispatchPacketReceive(playerData, packet);
            }
            case FALL_DAMAGE -> {
                var fdp = message.getFallDamage();
                UUID playerId = UUID.fromString(fdp.getPlayerId());
                PlayerData playerData = playerRegistry.getOrCreate(playerId);
                InboundPacket packet = new FallDamagePacket(playerId, fdp.getTrackedFallDistance(), fdp.getTick());
                packetEventBus.dispatchPacketReceive(playerData, packet);
            }
            case BLOCK_PLACE -> {
                var bpp = message.getBlockPlace();
                UUID playerId = UUID.fromString(bpp.getPlayerId());
                PlayerData playerData = playerRegistry.getOrCreate(playerId);
                InboundPacket packet = new BlockPlacePacket(
                        playerId, bpp.getBlockX(), bpp.getBlockY(), bpp.getBlockZ(), bpp.getTick());
                packetEventBus.dispatchPacketReceive(playerData, packet);
            }
            case HEARTBEAT -> {
                for (String idStr : message.getHeartbeat().getOnlinePlayerIdsList()) {
                    UUID id = UUID.fromString(idStr);
                    PlayerData data = playerRegistry.get(id);
                    if (data != null) {
                        playerRegistry.touch(id);
                        packetEventBus.dispatchTick(data);
                    }
                }
            }
            case HELLO -> handleHello(message.getHello(), connection);
            case PAYLOAD_NOT_SET -> {
                // Nothing to do - an empty message is not an error, just a no-op.
            }
        }
    }

    private void handleHello(io.wildsmp.anticheat.protocol.Hello hello, StreamObserver<ServerMessage> connection) {
        LicenseStore.ServerRecord record;
        if (hello.getCode().isEmpty()) {
            record = licenseStore.mint(hello.getServerName(), hello.getMcVersion());
            connection.onNext(ServerMessage.newBuilder()
                    .setAssigned(io.wildsmp.anticheat.protocol.Assigned.newBuilder().setCode(record.code()))
                    .build());
            return;
        }
        java.util.Optional<LicenseStore.ServerRecord> found = licenseStore.find(hello.getCode());
        if (found.isEmpty()) {
            // A code this backend has never heard of (e.g. a fresh backend/DB) - treat like a first
            // contact rather than erroring, so a stale local license.yml self-heals.
            record = licenseStore.mint(hello.getServerName(), hello.getMcVersion());
            connection.onNext(ServerMessage.newBuilder()
                    .setAssigned(io.wildsmp.anticheat.protocol.Assigned.newBuilder().setCode(record.code()))
                    .build());
            return;
        }
        record = found.get();
        licenseStore.touch(record.code(), System.currentTimeMillis());
        sessionByCode.put(record.code(), connection);
        licenseStore.appendAudit(record.code(), "connected");

        if (record.state() != LicenseStore.State.APPROVED) {
            connection.onNext(ServerMessage.newBuilder()
                    .setPending(io.wildsmp.anticheat.protocol.PendingStatus.newBuilder().setCode(record.code()))
                    .build());
            return;
        }
        if (!licenseStore.bindInstance(record.code(), hello.getInstanceId(), remoteIpOf(connection))) {
            // Bound to a different instanceId - a leaked/copied code. Keep replying pending, never
            // send the signed approved frame to an install that isn't the real bound one.
            connection.onNext(ServerMessage.newBuilder()
                    .setPending(io.wildsmp.anticheat.protocol.PendingStatus.newBuilder().setCode(record.code()))
                    .build());
            return;
        }
        pushSignedFrame(record.code(), "approved", connection);
    }

    /**
     * Also called by the panel's accept/disable routes to push a live update without waiting for
     * reconnect - and by handleHello on every routine reconnect for an already-approved server.
     * Always bumps seq itself (via {@link LicenseStore#nextSeq}) rather than reusing whatever seq
     * is currently on record, so every push - including a re-approval that repeats the exact same
     * "approved" type and code - gets a value the client's replay protection has never seen.
     */
    public void pushSignedFrame(String code, String type, StreamObserver<ServerMessage> connection) {
        long seq = licenseStore.nextSeq(code);
        long issuedAt = System.currentTimeMillis();
        String canonical = Ed25519KeyManager.canonicalString(type, code, seq, issuedAt);
        byte[] signature = keyManager.sign(canonical);
        connection.onNext(ServerMessage.newBuilder()
                .setSigned(io.wildsmp.anticheat.protocol.SignedMessage.newBuilder()
                        .setType(type).setCode(code).setSeq(seq).setIssuedAtMillis(issuedAt)
                        .setSignature(com.google.protobuf.ByteString.copyFrom(signature)))
                .build());
    }

    public StreamObserver<ServerMessage> sessionFor(String code) {
        return sessionByCode.get(code);
    }

    /** No-op if that server isn't currently connected - it'll just check on its own next periodic timer instead. */
    public void pushForceUpdateCheck(String code) {
        StreamObserver<ServerMessage> connection = sessionByCode.get(code);
        if (connection != null) {
            connection.onNext(ServerMessage.newBuilder()
                    .setForceUpdateCheck(io.wildsmp.anticheat.protocol.ForceUpdateCheck.newBuilder())
                    .build());
        }
    }

    @Override
    public void correctPosition(UUID playerId, double x, double y, double z) {
        StreamObserver<ServerMessage> connection = sessionByPlayer.get(playerId);
        if (connection == null) {
            return; // player's connection dropped between the flag and this call
        }
        connection.onNext(ServerMessage.newBuilder()
                .setCorrectPosition(io.wildsmp.anticheat.protocol.CorrectPosition.newBuilder()
                        .setPlayerId(playerId.toString())
                        .setX(x).setY(y).setZ(z))
                .build());
    }

    private static String remoteIpOf(StreamObserver<ServerMessage> connection) {
        // Captured into Context by RemoteAddressInterceptor (registered in Main.java) - handle(...)
        // and everything it calls runs inside that call's Context, so Context.current() here is
        // the right call's address, not a stale/shared one.
        java.net.SocketAddress address = RemoteAddressInterceptor.REMOTE_ADDR_KEY.get();
        return address == null ? "unknown" : address.toString();
    }

    private void onViolation(Violation violation) {
        StreamObserver<ServerMessage> connection = sessionByPlayer.get(violation.playerId());
        if (connection == null) {
            return; // player's connection dropped between the flag and this listener firing
        }
        ServerMessage verdict = ServerMessage.newBuilder()
                .setVerdict(Verdict.newBuilder()
                        .setPlayerId(violation.playerId().toString())
                        .setPlayerName(violation.playerName())
                        .setCheckName(violation.checkName())
                        .setCategory(categoryName(violation.category()))
                        .setVl(violation.vl())
                        .setThreshold(violation.threshold())
                        .setDetails(violation.details() == null ? "" : violation.details())
                        .setTimestampMillis(violation.timestampMillis()))
                .build();
        connection.onNext(verdict);
    }

    private static String categoryName(CheckCategory category) {
        return category.name();
    }
}
