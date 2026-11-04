package io.wildsmp.anticheat.bukkit;

import com.github.retrooper.packetevents.PacketEvents;
import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import io.wildsmp.anticheat.bukkit.scheduling.FoliaSchedulerAdapter;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;
import io.wildsmp.anticheat.core.check.movement.VanillaPhysics;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.logging.Level;

public class AnticheatBukkitPlugin extends JavaPlugin {

    private FoliaSchedulerAdapter scheduler;
    private PlayerDataManager playerDataManager;
    private BackendClient backendClient;
    private io.wildsmp.anticheat.bukkit.backend.BackendConnectionState connectionState;
    private io.wildsmp.anticheat.bukkit.relay.ProxyRelay proxyRelay;
    private io.wildsmp.anticheat.bukkit.bansystem.BanSystemIntegration banSystemIntegration;
    private io.wildsmp.anticheat.bukkit.alerts.AlertStreamManager alertStreamManager;
    private final io.wildsmp.anticheat.bukkit.license.LauncherLicenseClient launcherLicense=new io.wildsmp.anticheat.bukkit.license.LauncherLicenseClient();
    private TickClock tickClock;

    private volatile boolean combatRunning;
    private org.bukkit.configuration.file.YamlConfiguration combatConfig;

    protected final void startCombat() {
        loadCombatConfig();
        if (!new java.io.File(getDataFolder(), "backend-cert.pem").exists()) saveResource("backend-cert.pem", false);
        combatRunning = true;
        this.scheduler = new FoliaSchedulerAdapter();
        this.tickClock = new TickClock();
        scheduler.runRepeating(this, tickClock::advance, 1L, 1L);
        this.playerDataManager = new PlayerDataManager();
        // Backfill anyone already online (e.g. a plugin reload) - PlayerJoinEvent only fires for
        // players who join after this point, and onEnable itself runs on the main thread so this
        // is a safe place to call getEntityId().
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            scheduler.runOnPlayer(this, online, () -> {
                playerDataManager.trackEntityId(online.getEntityId(), online.getUniqueId());
                playerDataManager.initializeOffhand(online.getUniqueId(), online.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING);
            }, 1L);
        }

        long gracePeriodMillis = combatConfig().getLong("backend.fail-closed-grace-period-seconds", 5L) * 1000L;
        this.connectionState = new io.wildsmp.anticheat.bukkit.backend.BackendConnectionState(gracePeriodMillis);
        connectionState.onDisconnected(System.currentTimeMillis());

        String serverId = combatConfig().getString("server-id", "unknown-server");
        this.backendClient = new BackendClient(
                combatConfig().getString("backend.host", "127.0.0.1"),
                combatConfig().getInt("backend.port", 9443),
                combatConfig().getBoolean("backend.tls", true),
                combatConfig().getString("backend.trust-cert-path", ""));
        backendClient.setLogger(getLogger());

        backendClient.addDisconnectListener(() -> {
            backendClient.setApproved(false);
            connectionState.onDisconnected(System.currentTimeMillis());
            getLogger().warning("Lost connection to anticheat backend - failing closed in "
                    + (gracePeriodMillis / 1000) + "s if not restored.");
            scheduleReconnect(serverId);
        });
        backendClient.setTlsServerName(combatConfig().getString("backend.tls-server-name", ""));
        backendClient.addLauncherApprovalListener(approval -> {
            if (!launcherLicense.accepts(approval)) return;
            backendClient.approveUntil(approval.getExpiresMillis());
            connectionState.onConnected();
            sendJoinForAllOnlinePlayers();
        });

        PacketEvents.getAPI().getEventManager().registerListener(
                new io.wildsmp.anticheat.bukkit.backend.CombatLockdownListener(connectionState, backendClient::isApproved));

        this.alertStreamManager = new io.wildsmp.anticheat.bukkit.alerts.AlertStreamManager();

        getServer().getPluginManager().registerEvents(
                new io.wildsmp.anticheat.bukkit.listener.PlayerLifecycleListener(
                        playerDataManager, backendClient, alertStreamManager),
                this);
        getServer().getPluginManager().registerEvents(
                new io.wildsmp.anticheat.bukkit.listener.MaceDamageListener(backendClient, tickClock, playerDataManager, getLogger()),
                this);
        getServer().getPluginManager().registerEvents(
                new io.wildsmp.anticheat.bukkit.listener.AutoTotemListener(backendClient, tickClock, playerDataManager, getLogger()),
                this);
        // Purely a passive recorder for updateNoFallTracking's own real-landing-driven detection -
        // see FallDamageEventListener's Javadoc for why it's correlated this way, not used as the
        // sole trigger.
        getServer().getPluginManager().registerEvents(
                new io.wildsmp.anticheat.bukkit.listener.LaunchImpulseListener(playerDataManager),
                this);
        // Other plugins' abilities (BlissGems launches/dashes/teleports/double jumps etc.) are
        // legitimate server-driven movement - see PluginMovementListener for how they're exempted.
        getServer().getPluginManager().registerEvents(
                new io.wildsmp.anticheat.bukkit.listener.PluginMovementListener(playerDataManager, tickClock),
                this);
        if (!scheduler.isFolia()) {
            scheduler.runRepeating(this, () -> forEachOnline(this::updateOffhandTotemTracking), 1L, 1L);
            scheduler.runRepeating(this, () -> forEachOnline(this::updateMovementContextTracking), 1L, 1L);
        }
        PacketEvents.getAPI().getEventManager().registerListener(
                new io.wildsmp.anticheat.bukkit.packet.AnticheatPacketListener(
                        playerDataManager, backendClient, tickClock, playerDataManager::resolvePlayerId));
        PacketEvents.getAPI().getEventManager().registerListener(
                new io.wildsmp.anticheat.bukkit.packet.TransactionSyncListener(playerDataManager, tickClock));
        scheduler.runRepeating(this, this::pingAllPlayersForTickSync, 10L, 10L);

        // Replaces the old in-process per-tick PacketEventBus.dispatchTick loop - now a heartbeat
        // over the wire naming every online player, driving KillauraCheck's deferred evaluation
        // backend-side.
        scheduler.runRepeating(this, () -> backendClient.sendHeartbeat(
                getServer().getOnlinePlayers().stream().map(org.bukkit.entity.Player::getUniqueId).toList()), 1L, 1L);

        this.proxyRelay = combatConfig().getBoolean("relay-to-proxy", false)
                ? new io.wildsmp.anticheat.bukkit.relay.ProxyRelay(this)
                : null;

        this.banSystemIntegration = combatConfig().getBoolean("bansystem-integration.enabled", true)
                ? new io.wildsmp.anticheat.bukkit.bansystem.BanSystemIntegration(
                        this, scheduler, combatConfig().getString("bansystem-integration.command", "bsint-anticheat"))
                : null;

        backendClient.addVerdictListener(violation -> scheduler.runOnce(this, () -> {
            getServer().getPluginManager().callEvent(
                    new io.wildsmp.anticheat.bukkit.event.AnticheatViolationEvent(violation));
        }, 1L));
        if (scheduler.isFolia()) {
            // Folia: every player's own tracking runs on THAT player's region thread (reading blocks, potion effects,
            // inventories and teleporting are only legal there); one global task touching them throws every tick.
            getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                    startPerPlayerTicks(event.getPlayer());
                }

                @org.bukkit.event.EventHandler
                public void onRespawn(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent event) {
                    startPerPlayerTicks(event.getPlayer());
                }

                @org.bukkit.event.EventHandler
                public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
                    startPerPlayerTicks(event.getPlayer());
                }
            }, this);
            startTrackingWatchdog();
            for (Player online : getServer().getOnlinePlayers()) {
                startPerPlayerTicks(online);
            }
        }
        if (banSystemIntegration != null) {
            backendClient.addVerdictListener(banSystemIntegration::onViolation);
        }
        if (proxyRelay != null) {
            backendClient.addVerdictListener(proxyRelay::relay);
        }
        backendClient.addVerdictListener(v -> alertStreamManager.broadcast(v, this));

        java.util.Objects.requireNonNull(getCommand("anticheat")).setExecutor(
                new io.wildsmp.anticheat.bukkit.command.AnticheatCommand(this, alertStreamManager));

        backendClient.connect();
        backendClient.sendHello(launcherLicense.hello(serverId, getServer().getBukkitVersion()));
        scheduler.runRepeating(this, () -> {
            if (!combatRunning) return;
            try { backendClient.sendHello(launcherLicense.hello(serverId, getServer().getBukkitVersion())); }
            catch (RuntimeException e) { backendClient.setApproved(false); connectionState.onDisconnected(System.currentTimeMillis()); }
        }, 400L, 400L);

        getLogger().info("AC combat agent enabled, backend=" + serverId
                + (scheduler.isFolia() ? " (Folia detected)." : "."));
    }

    protected final void failCombat(Throwable error) {
        stopCombat();
        var locked = new io.wildsmp.anticheat.bukkit.backend.BackendConnectionState(0);
        locked.onDisconnected(0);
        PacketEvents.getAPI().getEventManager().registerListener(new io.wildsmp.anticheat.bukkit.backend.CombatLockdownListener(locked));
        getLogger().log(Level.SEVERE, "AC combat could not start; combat is locked while movement remains independent.", error);
    }

    protected final void stopCombat() {
        combatRunning = false;
        tickGeneration.clear();
        if (backendClient != null) {
            backendClient.shutdown();
        }

    }


    private static final long RECONNECT_DELAY_TICKS = 200L; // 10s - avoid hammering the backend on a real outage

    /**
     * {@link BackendClient} never retries on its own - a dropped stream (backend restart, network
     * blip, etc.) would otherwise leave this server permanently disconnected until someone notices
     * and restarts it, with every check silently doing nothing the whole time since no packets are
     * reaching the backend. Every disconnect reschedules this, so the retry keeps going until the
     * backend is reachable again.
     */
    private void scheduleReconnect(String serverId) {
        if (!combatRunning || !isEnabled()) return;
        scheduler.runOnce(this, () -> attemptReconnect(serverId), RECONNECT_DELAY_TICKS);
    }

    private void attemptReconnect(String serverId) {
        if (!combatRunning || !isEnabled()) return;
        try {
            backendClient.connect();
            backendClient.sendHello(launcherLicense.hello(serverId, getServer().getBukkitVersion()));
            // A backend restart wipes its in-memory PlayerRegistry - without re-sending join for
            // everyone already online here too (not just in onEnable), the backend first learns
            // about them again from a movement/attack packet instead of a real join, permanently
            // stuck showing a placeholder name in violation alerts for the rest of this session.
            sendJoinForAllOnlinePlayers();
            getLogger().info("Reconnected to the anticheat backend.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Reconnect attempt failed - will retry", e);
            scheduleReconnect(serverId);
        }
    }

    private void sendJoinForAllOnlinePlayers() {
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            backendClient.sendJoin(online.getUniqueId(), online.getName());
        }
    }

    /** Bumped every time a player's tracking task is (re)started so any older task for them stops itself. */
    private final java.util.Map<java.util.UUID, Integer> tickGeneration = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, Long> watchdogWarned = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean perPlayerTickFailureLogged = new java.util.concurrent.atomic.AtomicBoolean();

    private void forEachOnline(java.util.function.Consumer<Player> action) {
        for (Player player : getServer().getOnlinePlayers()) {
            action.accept(player);
        }
    }

    /** Folia only: starts this player's own once-a-tick tracking on the player's region thread (idempotent). */
    private void startPerPlayerTicks(Player player) {
        java.util.UUID id = player.getUniqueId();
        int mine = tickGeneration.merge(id, 1, Integer::sum);
        player.getScheduler().runAtFixedRate(this, task -> {
            if (!combatRunning || !player.isOnline() || tickGeneration.getOrDefault(id, 0) != mine) {
                task.cancel(); // a newer task took over, or the player left
                return;
            }
            try {
                updateOffhandTotemTracking(player);
                updateMovementContextTracking(player);

            } catch (RuntimeException e) {
                if (perPlayerTickFailureLogged.compareAndSet(false, true)) {
                    getLogger().log(java.util.logging.Level.WARNING,
                            "Per-player tracking failed for " + player.getName() + " (further failures are not logged)", e);
                }
            }
        }, null, 1L, 1L);
    }

    /**
     * Folia only: restarts the tracking of any player whose once-a-tick update has stopped (the task is bound to the player
     * entity and is not restarted by anything but a join, so a retired task used to leave terrain and effects frozen at their
     * defaults for the rest of the session: ladders, water and vines then looked like flying).
     */
    private void startTrackingWatchdog() {
        getServer().getAsyncScheduler().runAtFixedRate(this, t -> {
            long now = tickClock.current();
            for (Player player : getServer().getOnlinePlayers()) {
                PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(player.getUniqueId());
                long last = pose.lastContextTick;
                if (last == Long.MIN_VALUE || now - last <= 20L) {
                    continue;
                }
                startPerPlayerTicks(player);
                long wall = System.currentTimeMillis();
                Long warned = watchdogWarned.get(player.getUniqueId());
                if (warned == null || wall - warned > 60_000L) {
                    watchdogWarned.put(player.getUniqueId(), wall);
                    getLogger().warning("Movement tracking for " + player.getName() + " had stopped " + (now - last)
                            + " ticks ago; restarted it.");
                }
            }
        }, 3L, 1L, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** What ChronosAC currently sees for this player, for {@code /anticheat debug}. Call on the player's own thread. */
    public java.util.List<String> describeMovementState(Player player) {
        PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(player.getUniqueId());
        Block feet = player.getLocation().getBlock();
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("feet block: " + feet.getType() + ", below: " + feet.getRelative(BlockFace.DOWN).getType()
                + ", eyes: " + player.getEyeLocation().getBlock().getType());
        lines.add("server says: climbing=" + player.isClimbing() + " inWater=" + player.isInWater()
                + " swimming=" + player.isSwimming() + " gliding=" + player.isGliding() + " riding=" + player.isInsideVehicle()
                + " onGround=" + player.isOnGround() + " riptiding=" + player.isRiptiding());
        lines.add("terrain right now: " + resolveTerrainKind(player) + "   terrain being sent: " + pose.terrain
                + "   last update: " + (pose.lastContextTick == Long.MIN_VALUE ? "never" : (tickClock.current() - pose.lastContextTick) + " ticks ago"));
        lines.add("launched=" + pose.launchedAndAirborne + " fallImpulseExempt=" + pose.fallImpulseExempt
                + " effects: swimming=" + pose.effects.swimming() + " flightAllowed=" + pose.effects.flightAllowed()
                + " slowFalling=" + pose.effects.slowFalling() + " levitation=" + pose.effects.levitation());
        return lines;
    }

    private void moveTo(Player player, org.bukkit.Location location) {
        if (scheduler.isFolia()) {
            player.teleportAsync(location);
        } else {
            player.teleport(location);
        }
    }

    private void pingAllPlayersForTickSync() {
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            var tracker = playerDataManager.tickSync(player.getUniqueId());
            int id = tracker.sendTransaction(tickClock.current());
            com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing pingPacket =
                    new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing(id);
            com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().sendPacket(player, pingPacket);
        }
    }

    // How close a real InventoryClickEvent/PlayerSwapHandItemsEvent has to be, tick-wise, to the
    // moment the offhand poll below notices a new totem, to count as "that's what explains it" -
    // a same-tick-ish window accounting for event/scheduler ordering within one server tick, not a
    // detection threshold (the actual legitimacy check is binary: was there a real event at all).
    private static final long OFFHAND_LEGITIMACY_CORRELATION_TICKS = 2;

    /**
     * Notices, one tick at a time, whenever a player's offhand newly becomes a Totem of Undying,
     * and decides right then whether a real {@link org.bukkit.event.inventory.InventoryClickEvent}
     * or {@link org.bukkit.event.player.PlayerSwapHandItemsEvent} explains it - see {@link
     * PlayerDataManager.OffhandTotemState} for why that's the right question (not how fast it
     * happened). If neither fired recently, the totem couldn't have gotten there through any
     * legitimate path, so it's stripped back out immediately and reported - not tied to a specific
     * inventory event itself, so it can't be dodged by whichever exact path a cheat uses.
     */
    private void updateOffhandTotemTracking(Player player) {
        if (CombatExemptions.isExempt(player.getUniqueId()) || !backendClient.isApproved()) return;
        long now = tickClock.current();
        {
            PlayerDataManager.OffhandTotemState state = playerDataManager.offhandTotemState(player.getUniqueId());
            boolean hasTotem = player.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING;
            if (hasTotem) {
                if (state.equippedSinceTick == Long.MIN_VALUE) {
                    long sinceLegitimateAction = state.lastLegitimateActionTick == Long.MIN_VALUE
                            ? Long.MAX_VALUE : now - state.lastLegitimateActionTick;
                    boolean legitimate = sinceLegitimateAction <= OFFHAND_LEGITIMACY_CORRELATION_TICKS;
                    state.equippedSinceTick = now;
                    state.legitimatelyEquipped = legitimate;
                    if (!legitimate) {
                        player.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
                        getLogger().info("AutoTotem: player " + player.getName() + " got an offhand totem with no "
                                + "corresponding inventory click or hand-swap event - stripped it.");
                        backendClient.sendAutoTotem(player.getUniqueId(), now);
                        state.equippedSinceTick = Long.MIN_VALUE;
                    }
                }
            } else {
                state.equippedSinceTick = Long.MIN_VALUE;
            }
        }
    }

    // How long to wait, after a real confirmed landing that owed fall damage, before deciding
    // whether it actually applied - vanilla resolves fall damage the same tick as landing, so this
    // is generous headroom for scheduler/event ordering, not a detection threshold.
    private static final long FALL_CHECK_GRACE_TICKS = 3L;

    // Resolved here rather than inline in AnticheatPacketListener - that listener runs on
    // PacketEvents' Netty I/O thread, and Paper throws on touching live Bukkit block/potion-effect
    // state off the main thread (same reason PlayerDataManager caches entity-ID lookups). This
    // runs once per tick on the main thread and caches the result onto each player's LastKnownPose
    // for the packet listener to read synchronously.
    private void updateMovementContextTracking(Player player) {
        {
            PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(player.getUniqueId());
            pose.lastContextTick = tickClock.current();
            TerrainKind terrain = resolveTerrainKind(player);
            long wetNow = tickClock.current();
            boolean wet = terrain == TerrainKind.LIQUID_WATER || terrain == TerrainKind.LIQUID_LAVA
                    || terrain == TerrainKind.SUBMERGED;
            if (wet) {
                pose.lastWaterTick = wetNow;
            } else if (MovementGrace.waterGraceActive(wetNow, pose.lastWaterTick)) {
                terrain = TerrainKind.SUBMERGED; // just left the water: bobbing, hopping out, the shore boost
            }
            if (terrain == TerrainKind.CLIMBABLE) {
                pose.lastClimbTick = wetNow;
            } else if ((terrain == TerrainKind.NORMAL || terrain == TerrainKind.AIR)
                    && MovementGrace.climbGraceActive(wetNow, pose.lastClimbTick)) {
                terrain = TerrainKind.CLIMBABLE; // just climbed off the top: the climb's motion carries on briefly
            }
            pose.terrain = terrain;

            // Deliberately NOT Player#isOnGround() - on plain vanilla/Paper the server treats the
            // movement packet's own onGround field as authoritative for that state (confirmed
            // against the protocol docs; this is precisely the mechanism the classic NoFall trick
            // exploits), so isOnGround() just reflects the same client-controlled flag under a
            // different name. Checking the real block under the player's feet - the same approach
            // updateFlyCorrectionTracking's own landing detection already uses - can't be spoofed
            // by anything the client reports.
            boolean riptiding = player.isRiptiding();
            if (riptiding) {
                // the whole spin is a server-granted launch, and vanilla ignores its fall damage
                pose.launchedAndAirborne = true;
                pose.fallImpulseExempt = true;
                pose.lastLaunchTick = tickClock.current();
            }
            if (player.isInsideVehicle() || onBouncyBlock(player) || swimmingBoosted(player)) {
                // riding is not the player's own movement, and a slime block or bed throws them up higher than any jump
                pose.launchedAndAirborne = true;
                pose.lastLaunchTick = tickClock.current();
            }
            if (inCobweb(player)) {
                // a cobweb holds the player in place and nearly cancels gravity: hanging in one in mid-air is "airborne" for
                // far longer than any jump, which Fly would read as flying. Treat it like any other server-held movement.
                pose.launchedAndAirborne = true;
                pose.lastLaunchTick = tickClock.current();
            }
            boolean rawOnGroundNow = isReallyOnSolidGround(player);
            // Requires two consecutive raw-true readings before treating it as a genuine landing -
            // a fast fall brushing past a ledge/overhang for exactly one tick would otherwise
            // register as a spurious landing mid-fall, false-flagging a completely legitimate fall.
            boolean isOnGroundNow = rawOnGroundNow && pose.rawOnGroundLastTick;
            pose.rawOnGroundLastTick = rawOnGroundNow;

            long now = tickClock.current();
            // The client only leaves the ground a tick or two after a server velocity packet, so
            // clearing on the first grounded reading would end a launch's exemption before its arc
            // even starts - hold it for MovementGrace.LAUNCH_GRACE_TICKS first.
            if (isOnGroundNow && !MovementGrace.launchGraceActive(now, pose.lastLaunchTick)) {
                pose.launchedAndAirborne = false;
                pose.fallImpulseExempt = false;
            }
            if (player.getAllowFlight()) {
                pose.lastAllowFlightTick = now;
            }
            boolean flightAllowed = player.getAllowFlight()
                    || MovementGrace.flightStillAllowed(now, pose.lastAllowFlightTick);

            pose.effects = resolveMovementEffects(player, pose.launchedAndAirborne, flightAllowed);
        }
    }

    /**
     * True while the player is in a state where vanilla deals no fall damage: invulnerable, flying-
     * capable (creative/spectator/ability flight), gliding, riding, climbing, in water or lava,
     * slow-falling/levitating, or inside a block that negates the fall (cobweb, vines, powder snow...).
     */
    private static boolean noFallExemptNow(Player player) {
        return player.getAllowFlight() || player.isInvulnerable() || player.isGliding() || player.isRiptiding()
                || player.isInsideVehicle() || player.isClimbing() || player.isInWater() || player.isInLava()
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || NoFallRules.negatesFallDamage(player.getLocation().getBlock().getType().name());
    }

    /**
     * Liquid anywhere on the player's body, for the moments {@code isInWater()} lags the real position: the feet, the
     * block above them, the eyes, or a waterlogged block (slabs, stairs, seagrass...) or bubble column around them.
     */
    private static boolean touchesLiquid(Player player, Block feet) {
        Block mid = feet.getRelative(BlockFace.UP);
        Block eyes = player.getEyeLocation().getBlock();
        for (Block block : new Block[] {feet, mid, eyes}) {
            if (block.isLiquid() || block.getType() == Material.BUBBLE_COLUMN
                    || (block.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged())) {
                return true;
            }
        }
        return false;
    }

    /** In water with Dolphin's Grace or Depth Strider boots: swims several times faster than the plain water speed limit. */
    private static boolean swimmingBoosted(Player player) {
        if (!player.isInWater()) {
            return false;
        }
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            return true;
        }
        org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER) > 0;
    }

    /** A honey block beside the player's feet or chest (the wall they would slide down). */
    private static boolean slidingOnHoney(Block feet) {
        Block chest = feet.getRelative(BlockFace.UP);
        for (BlockFace face : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (feet.getRelative(face).getType() == Material.HONEY_BLOCK || chest.getRelative(face).getType() == Material.HONEY_BLOCK) {
                return true;
            }
        }
        return false;
    }

    /** Standing on (or just above) a slime block or a bed: both bounce the player higher than a jump. */
    private static boolean onBouncyBlock(Player player) {
        Material below = player.getLocation().subtract(0, 0.2, 0).getBlock().getType();
        return below == Material.SLIME_BLOCK || org.bukkit.Tag.BEDS.isTagged(below);
    }

    private static boolean inCobweb(Player player) {
        return player.getLocation().getBlock().getType() == Material.COBWEB
                || player.getEyeLocation().getBlock().getType() == Material.COBWEB;
    }

    private static boolean isReallyOnSolidGround(Player player) {
        org.bukkit.Location loc = player.getLocation();
        Block below = loc.getWorld().getBlockAt(
                (int) Math.floor(loc.getX()), (int) Math.floor(loc.getY() - 0.1), (int) Math.floor(loc.getZ()));
        return below.getType().isSolid();
    }

    /**
     * Detects and corrects a NoFall hack without trusting {@code onGround} in any form
     * (client-reported OR {@link Player#isOnGround()} - see {@link #isReallyOnSolidGround} for why
     * neither can be trusted). The moment a real landing (a real block found under the player's
     * feet, transitioning from not-there to there) is observed with {@code peakYSinceGrounded} far
     * enough above it to owe damage, starts a short pending window and checks whether vanilla's OWN
     * fall-damage system engaged AT ALL, ANYWHERE during the whole airborne stretch that just ended
     * (see {@link FallDamageEventListener} and {@code airborneStartTick}) - not whether a specific
     * damage AMOUNT resulted (kept producing false positives against every legitimate way vanilla
     * can reduce a hit: Protection, Resistance, Absorption, natural regen landing in the same
     * window), and not correlated against a narrow window around the LANDING tick specifically
     * (confirmed live: a player landing on a non-full-cube block - stairs/slab/fence, where the
     * crude solid-block point-check takes far longer to agree with vanilla's own collision than a
     * flat full block does - showed the real damage event firing 1.3 real seconds before this
     * check's own landing detection caught up; a narrow backward window missed it, and BOTH the
     * real damage and this check's own corrective damage ended up applying). If the event never
     * fired anywhere in the whole airborne stretch, applies the evaded damage directly (real fall
     * damage can't apply retroactively once they've already landed, so this is the "detect and
     * correct" equivalent of the Mace/AutoTotem real-time prevention pattern). Exempts {@code
     * allowFlight} (creative/spectator), Slow Falling, and a world with the {@code fallDamage}
     * gamerule disabled - all are legitimate zero-fall-damage cases, not evidence of anything.
     */
    private void updateNoFallTracking(Player player, PlayerDataManager.LastKnownPose pose, boolean isOnGroundNow,
                                      boolean rawOnGroundNow) {
        // A single tick of real ground contact after a drop within the safe distance restarts the
        // peak immediately - see NoFallRules for why (quick staircase descents never confirm the
        // debounced landing between steps and used to accumulate into a fake long fall).
        if (rawOnGroundNow && !isOnGroundNow
                && NoFallRules.shouldResetPeakOnGroundContact(pose.peakYSinceGrounded, player.getLocation().getY(),
                        VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS)) {
            pose.peakYSinceGrounded = player.getLocation().getY();
        }
        if (!isOnGroundNow && pose.wasReallyOnGround) {
            pose.airborneStartTick = tickClock.current(); // real liftoff - a fresh airborne stretch begins
            pose.airborneExempt = false;
        }
        // Any tick of this stretch where vanilla would deal no fall damage exempts the whole landing.
        if (!isOnGroundNow && noFallExemptNow(player)) {
            pose.airborneExempt = true;
        }

        boolean justLanded = isOnGroundNow && !pose.wasReallyOnGround;
        if (justLanded && !pose.fallCheckPending) {
            double fallDistance = pose.peakYSinceGrounded - player.getLocation().getY();
            Boolean fallDamageGameRule = player.getWorld().getGameRuleValue(org.bukkit.GameRule.FALL_DAMAGE);
            getLogger().fine("NoFall debug: " + player.getName() + " landed - fallDistance=" + fallDistance
                    + ", peakYSinceGrounded=" + pose.peakYSinceGrounded + ", landingY=" + player.getLocation().getY()
                    + ", allowFlight=" + player.getAllowFlight() + ", gameMode=" + player.getGameMode()
                    + ", fallDamageGameRule=" + fallDamageGameRule
                    + ", safeDistance=" + VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS
                    + ", airborneExempt=" + pose.airborneExempt + ", invulnerable=" + player.isInvulnerable()
                    + ", landedOn=" + player.getLocation().subtract(0, 0.1, 0).getBlock().getType());
            // World-level "fallDamage" gamerule off means NO player, hacking or not, ever takes
            // fall damage - a server operator's own choice, not evidence of anything.
            // Every legitimate way vanilla deals NO fall damage (and fires no damage event) must be
            // exempt, or "no damage happened" reads as a hack - confirmed live: a whole server's
            // players dropping off a removed start platform while protected all flagged at once and
            // each got real corrective damage.
            boolean exempt = pose.airborneExempt || pose.fallImpulseExempt || noFallExemptNow(player)
                    || Boolean.FALSE.equals(fallDamageGameRule)
                    || NoFallRules.negatesFallDamage(player.getLocation().subtract(0, 0.1, 0).getBlock().getType().name())
                    || NoFallRules.inDamageImmunityFrames(player.getNoDamageTicks(), player.getMaximumNoDamageTicks());
            double safeDistance = NoFallRules.safeDistance(VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS,
                    potionLevel(player, PotionEffectType.JUMP_BOOST));
            if (!exempt && fallDistance > safeDistance) {
                pose.fallCheckPending = true;
                pose.fallCheckDistance = fallDistance;
                pose.fallCheckStartTick = tickClock.current();
                pose.fallCheckAirborneStartTick = pose.airborneStartTick;
                getLogger().fine("NoFall debug: " + player.getName() + " pending fall check started - startTick="
                        + pose.fallCheckStartTick + ", airborneStartTick=" + pose.airborneStartTick);
            }
        }
        if (isOnGroundNow) {
            pose.peakYSinceGrounded = player.getLocation().getY();
        }
        pose.wasReallyOnGround = isOnGroundNow;

        if (pose.fallCheckPending && tickClock.current() - pose.fallCheckStartTick >= FALL_CHECK_GRACE_TICKS) {
            boolean realDamageEventFired = pose.lastFallDamageEventTick >= pose.fallCheckAirborneStartTick;
            getLogger().fine("NoFall debug: " + player.getName() + " pending check resolved - airborneStartTick="
                    + pose.fallCheckAirborneStartTick + ", lastFallDamageEventTick=" + pose.lastFallDamageEventTick
                    + ", realDamageEventFired=" + realDamageEventFired);
            if (!realDamageEventFired) {
                // Roughly 1 damage per block beyond the safe distance - vanilla's real formula,
                // generously matched here, same "real fact then tolerance" reasoning as every other
                // padded constant in this project. Player#damage already respects armor/effects/
                // absorption through the normal damage pipeline, same as a real fall would.
                double owedDamage = pose.fallCheckDistance - VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS;
                getLogger().info("NoFall: player " + player.getName() + " fell " + pose.fallCheckDistance
                        + " blocks (past the " + VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS
                        + "-block safe distance) with vanilla's own fall-damage system never engaging - applying "
                        + owedDamage + " damage now.");
                if (combatConfig().getBoolean("nofall.apply-damage", true)) {
                    player.damage(owedDamage, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.FALL).build());
                }
                backendClient.sendFallDamage(player.getUniqueId(), pose.fallCheckDistance, tickClock.current());
            }
            pose.fallCheckPending = false;
        }
    }

    // Drives an in-progress FlyCorrectionState to the ground - real vanilla gravity acceleration,
    // horizontal position frozen at wherever the correction started, teleporting the player down one
    // tick at a time until a solid block is found underneath. Runs independent of whatever the
    // player's own client keeps sending - unlike the old one-shot "teleport back to last known-good
    // position" correction, this can't just leave a still-flying player looking frozen in place,
    // since it keeps pulling them down every tick regardless of whether the hack keeps fighting it.
    private void updateFlyCorrectionTracking(Player player) {
        {
            PlayerDataManager.FlyCorrectionState correction = playerDataManager.flyCorrection(player.getUniqueId());
            if (!correction.active) {
                return;
            }
            org.bukkit.World world = player.getWorld();
            if (correction.currentY <= world.getMinHeight()) {
                correction.active = false; // fell out of the world - give up rather than loop forever
                return;
            }

            correction.descentVelocity = (correction.descentVelocity - VanillaPhysics.GRAVITY_PER_TICK) * VanillaPhysics.AIR_DRAG;
            double nextY = correction.currentY + correction.descentVelocity;

            // The block the player's feet would end up INSIDE if they moved to nextY - not one
            // block further down, which was the original (buggy) off-by-one that let the player
            // keep sinking through solid ground before ever detecting a landing.
            Block landingBlock = world.getBlockAt(
                    (int) Math.floor(correction.anchorX), (int) Math.floor(nextY), (int) Math.floor(correction.anchorZ));
            if (landingBlock.getType().isSolid()) {
                double groundY = landingBlock.getY() + 1.0;
                moveTo(player, new org.bukkit.Location(world, correction.anchorX, groundY, correction.anchorZ,
                        player.getLocation().getYaw(), player.getLocation().getPitch()));
                correction.active = false;
                return;
            }

            correction.currentY = nextY;
            moveTo(player, new org.bukkit.Location(world, correction.anchorX, nextY, correction.anchorZ,
                    player.getLocation().getYaw(), player.getLocation().getPitch()));
        }
    }

    private static TerrainKind resolveTerrainKind(Player player) {
        Block feet = player.getLocation().getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        if (TerrainRules.standingInLiquid(feet.isLiquid(), below.getType().isSolid(), below.isLiquid())) {
            return feet.getType() == Material.LAVA ? TerrainKind.LIQUID_LAVA : TerrainKind.LIQUID_WATER;
        }
        if (player.isInWater() || player.isInLava() || touchesLiquid(player, feet)) {
            return TerrainKind.SUBMERGED; // walking along the bottom: in a liquid, but resting on solid ground
        }
        if (feet.getType() == Material.POWDER_SNOW || feet.getRelative(BlockFace.UP).getType() == Material.POWDER_SNOW) {
            return TerrainKind.SUBMERGED; // sinking in powder snow: held up and slowed like a liquid
        }
        if (!player.isOnGround() && slidingOnHoney(feet)) {
            return TerrainKind.CLIMBABLE; // honey slows the fall to a crawl, a wall-slide that looks like hovering
        }
        // every climbable block (ladder, vines, twisting/weeping/cave vines, scaffolding...) via the vanilla tag, plus whatever
        // the server itself says the player is climbing right now
        if (org.bukkit.Tag.CLIMBABLE.isTagged(feet.getType()) || feet.getType().name().contains("SCAFFOLDING")
                || player.isClimbing()) {
            return TerrainKind.CLIMBABLE;
        }
        return switch (below.getType()) {
            case ICE, FROSTED_ICE -> TerrainKind.ICE;
            case PACKED_ICE, BLUE_ICE -> TerrainKind.PACKED_ICE;
            case SLIME_BLOCK -> TerrainKind.SLIME;
            case HONEY_BLOCK -> TerrainKind.HONEY;
            case SOUL_SAND, SOUL_SOIL -> TerrainKind.SOUL_SAND;
            case AIR, CAVE_AIR, VOID_AIR -> TerrainKind.AIR;
            default -> TerrainKind.NORMAL;
        };
    }

    private static MovementEffects resolveMovementEffects(Player player, boolean recentlyLaunched, boolean flightAllowed) {
        int speed = potionLevel(player, PotionEffectType.SPEED);
        int slowness = potionLevel(player, PotionEffectType.SLOWNESS);
        int jumpBoost = potionLevel(player, PotionEffectType.JUMP_BOOST);
        boolean levitation = player.hasPotionEffect(PotionEffectType.LEVITATION);
        boolean slowFalling = player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        return new MovementEffects(speed, slowness, jumpBoost, levitation, slowFalling,
                player.isSprinting(), player.isSneaking(), player.isSwimming(), player.isGliding(),
                flightAllowed, recentlyLaunched);
    }

    private static int potionLevel(Player player, PotionEffectType type) {
        PotionEffect effect = player.getPotionEffect(type);
        return effect == null ? 0 : effect.getAmplifier() + 1;
    }

    public FoliaSchedulerAdapter scheduler() {
        return scheduler;
    }

    public PlayerDataManager playerDataManager() {
        return playerDataManager;
    }

    public BackendClient backendClient() {
        return backendClient;
    }

    public io.wildsmp.anticheat.bukkit.backend.BackendConnectionState connectionState() {
        return connectionState;
    }



    public void reloadFromDisk() {
        loadCombatConfig();
    }
    private org.bukkit.configuration.file.YamlConfiguration combatConfig() { return combatConfig; }

    private void loadCombatConfig() {
        java.nio.file.Path target = getDataFolder().toPath().resolve("chronos.yml");
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            if (!java.nio.file.Files.exists(target)) {
                if (!java.nio.file.Files.exists(target)) saveResource("chronos.yml", false);
            }
            combatConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(target.toFile());
            if (CombatEndpoint.migrate(combatConfig)) combatConfig.save(target.toFile());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot load AC combat configuration", e);
        }
    }
}
