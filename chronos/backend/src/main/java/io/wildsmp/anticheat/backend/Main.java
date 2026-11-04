package io.wildsmp.anticheat.backend;

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.TlsServerCredentials;
import io.wildsmp.anticheat.backend.grpc.AnticheatServiceImpl;
import io.wildsmp.anticheat.backend.grpc.RemoteAddressInterceptor;
import io.wildsmp.anticheat.backend.license.Ed25519KeyManager;
import io.wildsmp.anticheat.backend.license.LicenseStore;
import io.wildsmp.anticheat.backend.panel.LicensePanelServer;
import io.wildsmp.anticheat.backend.panel.PanelAuth;
import io.wildsmp.anticheat.backend.player.PlayerRegistry;
import io.wildsmp.anticheat.backend.plugin.PluginJarService;
import io.wildsmp.anticheat.core.check.CheckRegistry;
import io.wildsmp.anticheat.core.check.combat.ClickSpeedCheck;
import io.wildsmp.anticheat.core.check.combat.HitboxCheck;
import io.wildsmp.anticheat.core.check.combat.KillauraCheck;
import io.wildsmp.anticheat.core.check.combat.AutoTotemCheck;
import io.wildsmp.anticheat.core.check.combat.KillauraSilentAimCheck;
import io.wildsmp.anticheat.core.check.combat.MaceDamageCheck;
import io.wildsmp.anticheat.core.check.combat.PositionHistoryCheck;
import io.wildsmp.anticheat.core.check.combat.ReachCheck;
import io.wildsmp.anticheat.core.check.combat.TriggerBotCheck;
import io.wildsmp.anticheat.core.check.combat.VelocityCheck;
import io.wildsmp.anticheat.core.check.movement.FlyCheck;
import io.wildsmp.anticheat.core.check.movement.JesusCheck;
import io.wildsmp.anticheat.core.check.movement.NoFallCheck;
import io.wildsmp.anticheat.core.check.movement.ScaffoldCheck;
import io.wildsmp.anticheat.core.check.movement.ScaffoldSneakCheck;
import io.wildsmp.anticheat.core.check.movement.ScaffoldSpeedCheck;
import io.wildsmp.anticheat.core.check.movement.SpeedCheck;
import io.wildsmp.anticheat.core.check.movement.SpiderCheck;
import io.wildsmp.anticheat.core.check.movement.TimerCheck;
import io.wildsmp.anticheat.core.config.AnticheatConfig;
import io.wildsmp.anticheat.core.packet.PacketEventBus;
import io.wildsmp.anticheat.core.violation.ViolationManager;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    private static final Logger LOG = Logger.getLogger("chronosac-backend");

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, Object> rawConfig = loadRawConfig();

        AnticheatConfig config = AnticheatConfig.withDefaults(
                new AnticheatConfig.CheckSettings(true, 10.0, 1.0));
        config.load(new java.io.StringReader(new Yaml().dump(rawConfig)));

        ViolationManager violationManager = new ViolationManager(config);
        CheckRegistry checkRegistry = new CheckRegistry(violationManager);
        PacketEventBus packetEventBus = new PacketEventBus(checkRegistry,
                (checkName, throwable) -> LOG.log(Level.WARNING, "Check '" + checkName + "' failed", throwable));
        PlayerRegistry playerRegistry = new PlayerRegistry();

        java.nio.file.Path pluginJarPath = java.nio.file.Path.of(
                String.valueOf(rawConfig.getOrDefault("served-plugin-jar-path", "served-plugin/anticheat-bukkit.jar")));
        PluginJarService pluginJarService = new PluginJarService(pluginJarPath);
        if (pluginJarService.hasJar()) {
            LOG.info("Serving plugin jar for auto-update: " + pluginJarPath + " (" + pluginJarService.sizeBytes() + " bytes, sha256=" + pluginJarService.sha256() + ")");
        } else {
            LOG.info("No plugin jar configured to serve at " + pluginJarPath + " - auto-update is inert until one is baked into the image.");
        }

        LicenseStore licenseStore = new LicenseStore(
                java.nio.file.Path.of(String.valueOf(rawConfig.getOrDefault("license-db-path", "data/anticheat.db"))));
        Ed25519KeyManager keyManager = new Ed25519KeyManager(
                java.nio.file.Path.of(String.valueOf(rawConfig.getOrDefault("license-private-key-path", "ed25519-private.pem"))));
        AnticheatServiceImpl serviceImpl = new AnticheatServiceImpl(
                packetEventBus, violationManager, playerRegistry, pluginJarService, licenseStore, keyManager);

        checkRegistry.register(new PositionHistoryCheck());
        checkRegistry.register(new KillauraCheck(playerRegistry::get));
        checkRegistry.register(new KillauraSilentAimCheck(playerRegistry::get));
        checkRegistry.register(new ReachCheck(playerRegistry::get));
        checkRegistry.register(new ClickSpeedCheck());
        checkRegistry.register(new HitboxCheck(playerRegistry::get));
        checkRegistry.register(new TriggerBotCheck(playerRegistry::get));
        checkRegistry.register(new VelocityCheck(playerRegistry::get));
        checkRegistry.register(new MaceDamageCheck());
        checkRegistry.register(new AutoTotemCheck());
        checkRegistry.register(new SpeedCheck(serviceImpl));
        checkRegistry.register(new FlyCheck(serviceImpl));
        checkRegistry.register(new NoFallCheck());
        checkRegistry.register(new JesusCheck(serviceImpl));
        checkRegistry.register(new SpiderCheck(serviceImpl));
        checkRegistry.register(new TimerCheck());
        checkRegistry.register(new ScaffoldCheck());
        checkRegistry.register(new ScaffoldSpeedCheck());
        checkRegistry.register(new ScaffoldSneakCheck());

        long idleTimeoutMillis = TimeUnit.SECONDS.toMillis(
                asLong(rawConfig.get("player-idle-timeout-seconds"), 600L));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "anticheat-idle-sweep");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            List<java.util.UUID> evicted = playerRegistry.evictIdleOlderThan(idleTimeoutMillis, System.currentTimeMillis());
            for (java.util.UUID id : evicted) {
                violationManager.clearPlayer(id);
            }
            if (!evicted.isEmpty()) {
                LOG.info("Evicted " + evicted.size() + " idle player(s) from backend state.");
            }
        }, 1, 1, TimeUnit.MINUTES);

        java.security.PublicKey launcherKey;
        try (var keyStream=Main.class.getResourceAsStream("/evangelium-public.key")) {
            if(keyStream==null)throw new IOException("Missing Evangelium public verifier");
            launcherKey=io.knowledge.evangelium.Reply.publicKey(new String(keyStream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        } catch(java.security.GeneralSecurityException e){throw new IOException("Invalid Evangelium public verifier",e);}
        var launcherService=new io.wildsmp.anticheat.backend.grpc.LauncherAnticheatService(serviceImpl, launcherKey,
                !Boolean.FALSE.equals(rawConfig.get("allow-legacy-chronos")));
        int port = (int) asLong(rawConfig.get("listen-port"), 9443L);

        boolean tlsEnabled = rawConfig.get("tls") instanceof Map<?, ?> tlsMap
                && Boolean.TRUE.equals(tlsMap.get("enabled"));
        Server server;
        if (tlsEnabled) {
            Map<?, ?> tlsMap = (Map<?, ?>) rawConfig.get("tls");
            File certFile = new File(String.valueOf(tlsMap.get("cert-file")));
            File keyFile = new File(String.valueOf(tlsMap.get("key-file")));
            server = Grpc.newServerBuilderForPort(port, TlsServerCredentials.create(certFile, keyFile))
                    .addService(launcherService)
                    .intercept(new RemoteAddressInterceptor())
                    .build();
        } else {
            LOG.warning("TLS is DISABLED - only use this for local testing on a trusted loopback network.");
            server = io.grpc.ServerBuilder.forPort(port)
                    .addService(launcherService)
                    .intercept(new RemoteAddressInterceptor())
                    .build();
        }
        server.start();
        LOG.info("ChronosAC backend listening on port " + port + (tlsEnabled ? " (TLS)" : " (plaintext)"));

        int panelPort = (int) asLong(rawConfig.get("panel-port"), 9080L);
        String panelPassword = String.valueOf(rawConfig.getOrDefault("panel-password", "change-me"));
        String hmacSecret = String.valueOf(rawConfig.getOrDefault("panel-session-secret", "change-this-too-it-must-be-long"));
        PanelAuth panelAuth = new PanelAuth(panelPassword, hmacSecret);
        new LicensePanelServer(panelPort, licenseStore, panelAuth, serviceImpl).start();
        LOG.info("License panel listening on port " + panelPort);

        server.awaitTermination();
    }

    private static long asLong(Object value, long defaultValue) {
        return value instanceof Number n ? n.longValue() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadRawConfig() throws IOException {
        java.io.File external = new java.io.File("config.yml");
        try (Reader reader = external.exists()
                ? new FileReader(external, StandardCharsets.UTF_8)
                : new InputStreamReader(Main.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return loaded instanceof Map ? (Map<String, Object>) loaded : Map.of();
        }
    }
}
