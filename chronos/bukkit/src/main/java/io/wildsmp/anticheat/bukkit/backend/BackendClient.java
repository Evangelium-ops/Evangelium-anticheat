package io.wildsmp.anticheat.bukkit.backend;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.StreamObserver;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.violation.Violation;
import io.wildsmp.anticheat.protocol.AnticheatServiceGrpc;
import io.wildsmp.anticheat.protocol.AttackPacket;
import io.wildsmp.anticheat.protocol.AutoTotemPacket;
import io.wildsmp.anticheat.protocol.ClientMessage;
import io.wildsmp.anticheat.protocol.DamagePacket;
import io.wildsmp.anticheat.protocol.Hello;
import io.wildsmp.anticheat.protocol.JarChunk;
import io.wildsmp.anticheat.protocol.JarDownloadRequest;
import io.wildsmp.anticheat.protocol.MovementPacket;
import io.wildsmp.anticheat.protocol.PlayerJoin;
import io.wildsmp.anticheat.protocol.PlayerQuit;
import io.wildsmp.anticheat.protocol.ServerMessage;
import io.wildsmp.anticheat.protocol.SignedMessage;
import io.wildsmp.anticheat.protocol.TickHeartbeat;
import io.wildsmp.anticheat.protocol.UpdateCheckRequest;
import io.wildsmp.anticheat.protocol.UpdateCheckResponse;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The bukkit-side half of the gRPC stream to the backend. Owns exactly one bidirectional call for
 * the lifetime of the plugin (reopened on {@link #connect()} after a disconnect); every method
 * that sends data is a no-op if the stream isn't currently open, since {@link
 * BackendConnectionState} (fail-closed state machine) is what decides what to do about *not*
 * being connected - this class only knows how to talk to the backend when it can.
 */
public final class BackendClient {
    private volatile boolean stopping;
    private final io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup eventLoop =
            new io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup(1, (java.util.concurrent.ThreadFactory) task -> {
                Thread thread=new Thread(task, "AC-grpc-io"); thread.setDaemon(true); return thread;
            });
    private final java.util.concurrent.ExecutorService callbacks=java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(task, "AC-grpc-callback"); thread.setDaemon(true); return thread;
    });
    private volatile boolean approved;
    private volatile long approvalExpires=Long.MAX_VALUE;
    public void approveUntil(long expires) { approvalExpires=expires; approved=true; }
    public void setApproved(boolean approved) { this.approved = approved; }
    public boolean isApproved() { return approved && System.currentTimeMillis()<approvalExpires; }


    private final String host;
    private final int port;
    private final boolean tlsEnabled;
    private final String trustCertPath;
    private String tlsServerName="";
    public void setTlsServerName(String name) { tlsServerName=name==null ? "" : name; }
    private final List<Consumer<io.wildsmp.anticheat.protocol.LauncherApproval>> launcherListeners=new CopyOnWriteArrayList<>();
    public void addLauncherApprovalListener(Consumer<io.wildsmp.anticheat.protocol.LauncherApproval> listener) { launcherListeners.add(listener); }
    private final List<Consumer<Violation>> verdictListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> disconnectListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> assignedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> pendingListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SignedMessage>> signedListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> forceUpdateListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CorrectPositionPayload>> correctPositionListeners = new CopyOnWriteArrayList<>();

    private volatile ManagedChannel channel;
    private volatile AnticheatServiceGrpc.AnticheatServiceStub stub;
    private final AtomicReference<StreamObserver<ClientMessage>> outbound = new AtomicReference<>();
    private volatile Logger logger = Logger.getLogger("ChronosAC");

    /** Lets the owning plugin route this class's diagnostics through its own named logger. */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public BackendClient(String host, int port, boolean tlsEnabled) {
        this(host, port, tlsEnabled, null);
    }

    /**
     * @param trustCertPath path to a PEM file to trust directly instead of the JVM's system trust
     *                      store, or null/blank to use the system default. Needed for a self-signed
     *                      backend cert on a server where the JVM's own cacerts file isn't writable
     *                      (e.g. a Pterodactyl container with a read-only rootfs) - grpc supports
     *                      trusting a PEM file directly, so no system-level keytool import is
     *                      needed at all.
     */
    public BackendClient(String host, int port, boolean tlsEnabled, String trustCertPath) {
        this.host = host;
        this.port = port;
        this.tlsEnabled = tlsEnabled;
        this.trustCertPath = trustCertPath;
    }

    public void addVerdictListener(Consumer<Violation> listener) {
        verdictListeners.add(listener);
    }

    public void addDisconnectListener(Runnable listener) {
        disconnectListeners.add(listener);
    }

    public void addAssignedListener(Consumer<String> listener) {
        assignedListeners.add(listener);
    }

    public void addPendingListener(Consumer<String> listener) {
        pendingListeners.add(listener);
    }

    public void addSignedListener(Consumer<SignedMessage> listener) {
        signedListeners.add(listener);
    }

    public void addCorrectPositionListener(Consumer<CorrectPositionPayload> listener) {
        correctPositionListeners.add(listener);
    }

    public void addForceUpdateListener(Runnable listener) {
        forceUpdateListeners.add(listener);
    }

    public synchronized void connect() {
        if (stopping) return;
        approved = false;
        if (channel != null) {
            // Only relevant on a reconnect - the first connect() of the plugin's lifetime has no
            // prior channel. Without this, every retry during a real outage leaks the previous
            // ManagedChannel's threads/sockets instead of replacing it.
            channel.shutdownNow();
        }
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port)
                .eventLoopGroup(eventLoop)
                .channelType(io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel.class)
                .executor(callbacks).offloadExecutor(callbacks);
        if (tlsEnabled) {
            builder.sslContext(tlsContext()).useTransportSecurity();
        } else {
            builder.usePlaintext();
        }
        if (tlsEnabled && !tlsServerName.isBlank()) builder.overrideAuthority(tlsServerName);
        this.channel = builder.build();

        AnticheatServiceGrpc.AnticheatServiceStub stub = AnticheatServiceGrpc.newStub(channel);
        this.stub = stub;

        StreamObserver<ClientMessage> stream = stub.stream(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                switch (message.getPayloadCase()) {
                    case LAUNCHER_APPROVAL -> { for(var listener:launcherListeners) listener.accept(message.getLauncherApproval()); }
                    case VERDICT -> {
                        Violation violation = toViolation(message.getVerdict());
                        if (!CombatPolicy.accepts(violation.category(), isApproved())
                                || io.wildsmp.anticheat.bukkit.CombatExemptions.isExempt(violation.playerId())) break;
                        for (Consumer<Violation> listener : verdictListeners) {
                            listener.accept(violation);
                        }
                    }
                    case ASSIGNED -> {
                        for (Consumer<String> listener : assignedListeners) {
                            listener.accept(message.getAssigned().getCode());
                        }
                    }
                    case PENDING -> {
                        for (Consumer<String> listener : pendingListeners) {
                            listener.accept(message.getPending().getCode());
                        }
                    }
                    case SIGNED -> {
                        for (Consumer<SignedMessage> listener : signedListeners) {
                            listener.accept(message.getSigned());
                        }
                    }
                    case FORCE_UPDATE_CHECK -> {
                        for (Runnable listener : forceUpdateListeners) {
                            listener.run();
                        }
                    }
                    case CORRECT_POSITION -> { /* Grim owns movement correction in the combined AC. */ }
                    case PAYLOAD_NOT_SET -> {
                        // no-op
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                approved = false;
                if (stopping) return;
                // Previously discarded - every disconnect surfaced as a generic "Lost connection"
                // with zero diagnostic information about why. Logging the real cause here.
                logger.log(Level.WARNING, "Backend gRPC stream failed: " + t, t);
                outbound.set(null);
                for (Runnable listener : disconnectListeners) {
                    listener.run();
                }
            }

            @Override
            public void onCompleted() {
                approved = false;
                if (stopping) return;
                outbound.set(null);
                for (Runnable listener : disconnectListeners) {
                    listener.run();
                }
            }
        });
        outbound.set(stream);
    }

    public boolean isConnected() {
        return outbound.get() != null;
    }

    public void sendJoin(UUID id, String name) {
        send(ClientMessage.newBuilder().setJoin(
                PlayerJoin.newBuilder().setPlayerId(id.toString()).setPlayerName(name)).build());
    }

    public void sendQuit(UUID id) {
        send(ClientMessage.newBuilder().setQuit(
                PlayerQuit.newBuilder().setPlayerId(id.toString())).build());
    }

    public void sendMovement(UUID id, double x, double y, double z, float yaw, float pitch, boolean onGround,
                              long tick, TerrainKind terrain, MovementEffects effects, double estimatedTickLag) {
        send(ClientMessage.newBuilder().setMovement(
                MovementPacket.newBuilder().setPlayerId(id.toString())
                        .setX(x).setY(y).setZ(z).setYaw(yaw).setPitch(pitch).setOnGround(onGround).setTick(tick)
                        .setTerrainKind(terrain.ordinal())
                        .setSpeedLevel(effects.speedLevel()).setSlownessLevel(effects.slownessLevel())
                        .setJumpBoostLevel(effects.jumpBoostLevel()).setLevitation(effects.levitation())
                        .setSlowFalling(effects.slowFalling()).setSprinting(effects.sprinting())
                        .setSneaking(effects.sneaking()).setSwimming(effects.swimming()).setGliding(effects.gliding())
                        .setEstimatedTickLag(estimatedTickLag).setFlightAllowed(effects.flightAllowed())
                        .setRecentlyLaunched(effects.recentlyLaunched()))
                .build());
    }

    public void sendFallDamage(UUID playerId, double trackedFallDistance, long tick) {
        send(ClientMessage.newBuilder().setFallDamage(
                io.wildsmp.anticheat.protocol.FallDamagePacket.newBuilder().setPlayerId(playerId.toString())
                        .setTrackedFallDistance(trackedFallDistance).setTick(tick)).build());
    }

    public void sendBlockPlace(UUID playerId, int blockX, int blockY, int blockZ, long tick) {
        send(ClientMessage.newBuilder().setBlockPlace(
                io.wildsmp.anticheat.protocol.BlockPlacePacket.newBuilder().setPlayerId(playerId.toString())
                        .setBlockX(blockX).setBlockY(blockY).setBlockZ(blockZ).setTick(tick)).build());
    }

    public void sendAttack(UUID attackerId, UUID targetId, long perceivedTick) {
        send(ClientMessage.newBuilder().setAttack(
                AttackPacket.newBuilder().setAttackerId(attackerId.toString())
                        .setTargetId(targetId.toString()).setPerceivedTick(perceivedTick)).build());
    }

    public void sendDamage(UUID attackerId, UUID victimId, double damage, long tick) {
        send(ClientMessage.newBuilder().setDamage(
                DamagePacket.newBuilder().setAttackerId(attackerId.toString())
                        .setVictimId(victimId.toString()).setDamage(damage).setTick(tick)).build());
    }

    /** Only ever sent once bukkit has already confirmed an offhand totem had no legitimate cause - see AutoTotemListener. */
    public void sendAutoTotem(UUID playerId, long tick) {
        send(ClientMessage.newBuilder().setAutoTotem(
                AutoTotemPacket.newBuilder().setPlayerId(playerId.toString()).setTick(tick)).build());
    }

    public void sendHeartbeat(Collection<UUID> onlinePlayerIds) {
        TickHeartbeat.Builder heartbeat = TickHeartbeat.newBuilder();
        for (UUID id : onlinePlayerIds) {
            heartbeat.addOnlinePlayerIds(id.toString());
        }
        send(ClientMessage.newBuilder().setHeartbeat(heartbeat).build());
    }

    public void sendHello(Hello hello) {
        send(ClientMessage.newBuilder().setHello(hello).build());
    }

    public void checkForUpdate(Consumer<UpdateCheckResponse> onResult, Consumer<Throwable> onError) {
        AnticheatServiceGrpc.AnticheatServiceStub currentStub = stub;
        if (currentStub == null) {
            return; // not connected - PluginUpdateChecker just tries again next interval
        }
        currentStub.checkForUpdate(UpdateCheckRequest.newBuilder().build(), new StreamObserver<>() {
            @Override
            public void onNext(UpdateCheckResponse value) {
                onResult.accept(value);
            }

            @Override
            public void onError(Throwable t) {
                onError.accept(t);
            }

            @Override
            public void onCompleted() {
                // no-op - onResult already delivered the single response
            }
        });
    }

    public void downloadPluginJar(Consumer<byte[]> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        AnticheatServiceGrpc.AnticheatServiceStub currentStub = stub;
        if (currentStub == null) {
            onError.accept(new IllegalStateException("Not connected to backend"));
            return;
        }
        currentStub.downloadPluginJar(JarDownloadRequest.newBuilder().build(), new StreamObserver<>() {
            @Override
            public void onNext(JarChunk value) {
                onChunk.accept(value.getData().toByteArray());
            }

            @Override
            public void onError(Throwable t) {
                onError.accept(t);
            }

            @Override
            public void onCompleted() {
                onComplete.run();
            }
        });
    }

    // gRPC's StreamObserver.onNext()/onCompleted() are explicitly not safe to call concurrently -
    // PacketEvents dispatches each connection's packets on that connection's own Netty event-loop
    // thread, so sendMovement/sendAttack/etc. from two different players can otherwise race here
    // and corrupt the shared MessageFramer's buffer (seen live as a BufferOverflowException).
    // Synchronizing on the client instance serializes every write against connect()/shutdown() too.
    private synchronized void send(ClientMessage message) {
        if (!message.hasHello() && !isApproved()) return;
        StreamObserver<ClientMessage> stream = outbound.get();
        if (stream == null) {
            return; // not connected - BackendConnectionState is what reacts to this
        }
        try {
            stream.onNext(message);
        } catch (RuntimeException e) {
            outbound.set(null);
        }
    }

    public void shutdown() {
        ManagedChannel closing;
        StreamObserver<ClientMessage> stream;
        synchronized (this) {
            if (stopping) return;
            stopping=true;approved=false;
            stream=outbound.getAndSet(null);closing=channel;channel=null;
        }
        // Do not hold the send lock while waiting for callbacks that may themselves finish a send.
        if (stream!=null) { try { stream.onCompleted(); } catch (RuntimeException ignored) { } }
        if (closing!=null) {
            closing.shutdownNow();
            try { closing.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        eventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        callbacks.shutdownNow();
        try { callbacks.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private SslContext tlsContext() {
        SslContextBuilder builder = GrpcSslContexts.forClient()
                // grpc-netty-shaded's default native OpenSSL/BoringSSL engine doesn't reliably honor
                // a custom trust manager set via the high-level TlsChannelCredentials API - the
                // handshake falls back to validating against the JVM's default system trust store
                // instead, which fails PKIX validation for a self-signed cert. Forcing the plain JDK
                // provider here bypasses the native engine and makes the trust manager actually apply.
                .sslProvider(SslProvider.JDK)
                // GrpcSslContexts.forClient() picks its ApplicationProtocolConfig based on whether
                // native OpenSSL ALPN is on the classpath (it is, via netty-tcnative), so it defaults
                // to NPN_AND_ALPN regardless of the sslProvider() override above - and the JDK
                // provider only ever supports plain ALPN, so building with that default throws
                // UnsupportedOperationException. Override it explicitly to ALPN-only.
                .applicationProtocolConfig(new io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig(
                        io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN,
                        io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        io.grpc.netty.shaded.io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2));
        if (trustCertPath != null && !trustCertPath.isBlank()) {
            java.io.File certFile = new java.io.File(trustCertPath);
            if (!certFile.isFile()) {
                // Fail loudly rather than silently falling back to the system trust store, which
                // would just mean this same handshake failure resurfaces later with a much less
                // obvious cause.
                throw new IllegalStateException("backend.trust-cert-path is set to " + trustCertPath
                        + " but that file doesn't exist");
            }
            builder.trustManager(certFile);
        }
        try {
            return builder.build();
        } catch (javax.net.ssl.SSLException e) {
            throw new IllegalStateException("Failed to build TLS context"
                    + (trustCertPath != null && !trustCertPath.isBlank() ? " from trust cert " + trustCertPath : ""), e);
        }
    }

    private static Violation toViolation(io.wildsmp.anticheat.protocol.Verdict verdict) {
        return new Violation(
                UUID.fromString(verdict.getPlayerId()),
                verdict.getPlayerName(),
                verdict.getCheckName(),
                CheckCategory.valueOf(verdict.getCategory()),
                verdict.getVl(),
                verdict.getThreshold(),
                verdict.getDetails(),
                verdict.getTimestampMillis());
    }

    /** A decoded {@code ServerMessage.CorrectPosition} - see {@link #addCorrectPositionListener}. */
    public record CorrectPositionPayload(UUID playerId, double x, double y, double z) {
    }
}
