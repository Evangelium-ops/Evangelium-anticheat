package io.wildsmp.anticheat.bukkit;

import io.wildsmp.anticheat.core.check.combat.TickSyncTracker;
import io.wildsmp.anticheat.core.check.movement.MovementEffects;
import io.wildsmp.anticheat.core.check.movement.TerrainKind;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player bookkeeping that stays entirely on this Minecraft server and never crosses the wire
 * to the backend - today, just each player's {@link TickSyncTracker}, since the client-round-trip
 * ping measurement it does can only happen on the server the client is actually connected to.
 * (Before the remote-backend migration, this held the full core {@code PlayerData}; that now
 * lives in the backend's {@code PlayerRegistry} instead, keyed by the same player UUID.)
 */
public final class PlayerDataManager {

    private final Map<UUID, TickSyncTracker> tickSyncByPlayer = new ConcurrentHashMap<>();
    // Bukkit entity ID -> player UUID, populated on join (main thread) so packet listeners running
    // on PacketEvents' Netty I/O thread can resolve an attack packet's target without touching
    // live Bukkit world/entity state off-thread (Paper throws on that - see AnticheatPacketListener).
    private final Map<Integer, UUID> playerIdByEntityId = new ConcurrentHashMap<>();
    // The four "move player" client packet variants each carry only position, only rotation,
    // both, or neither - PacketEvents' shared wrapper reports the *absent* half as zero, not the
    // player's actual last position/rotation. AnticheatPacketListener merges a packet's changed
    // half with the cached other half from here before sending anything to the backend, so a
    // rotation-only packet (say) can never overwrite a player's real x/y/z with (0,0,0).
    private final Map<UUID, LastKnownPose> lastKnownPoseByPlayer = new ConcurrentHashMap<>();
    // Tracks whether each player's current offhand totem (if any) got there through a real,
    // observed inventory action - see OffhandTotemState.
    private final Map<UUID, OffhandTotemState> offhandTotemByPlayer = new ConcurrentHashMap<>();
    // Tracks an in-progress forced-descent correction (see FlyCorrectionState) for whichever
    // players currently have one running.
    private final Map<UUID, FlyCorrectionState> flyCorrectionByPlayer = new ConcurrentHashMap<>();

    public static final class LastKnownPose {
        public volatile double x;
        public volatile double y;
        public volatile double z;
        public volatile float yaw;
        public volatile float pitch;
        // Highest Y seen since this player was last on the ground - lets MaceDamageListener compute
        // a real, locally-tracked fall distance synchronously (no backend round trip) so it can
        // clamp an exploited hit's damage before it ever applies, not just detect it after the fact.
        public volatile double peakYSinceGrounded;
        // Updated once per tick on the main thread (AnticheatBukkitPlugin#updateMovementContextTracking),
        // never computed inline in AnticheatPacketListener - that listener runs on PacketEvents'
        // Netty I/O thread, and Paper throws on touching live Bukkit block/potion-effect state off
        // the main thread (same reason playerIdByEntityId below is a plain cached map).
        public volatile TerrainKind terrain = TerrainKind.NORMAL;
        public volatile MovementEffects effects = MovementEffects.none();
        // Set true by LaunchImpulseListener the moment a real server-driven velocity impulse
        // (wind charge, explosion, engine push) hits this player, cleared the moment
        // AnticheatBukkitPlugin#updateMovementContextTracking observes them touch real ground
        // again (Player#isOnGround(), server-authoritative - not the client-reported movement
        // packet flag). Covers the player's WHOLE resulting flight/fall arc, however long a big
        // multi-charge launch takes, rather than guessing a fixed grace-tick window.
        public volatile boolean launchedAndAirborne = false;
        // Vanilla skips fall damage (and fires no damage event) after a wind charge / explosion launch,
        // a mace smash attack and a riptide - it tracks an "ignore fall damage from the current impulse"
        // state we cannot read. Set by those events, cleared with launchedAndAirborne once the player is
        // really back on the ground; NoFall treats the whole landing as exempt while it is set.
        public volatile boolean fallImpulseExempt = false;
        // Ticks (TickClock) of the last server-driven launch/teleport, the last damage of any
        // cause, and the last time the server had flight allowed for this player - the raw inputs
        // to MovementGrace's window decisions. Long.MIN_VALUE means "never".
        public volatile long lastLaunchTick = Long.MIN_VALUE;
        public volatile long lastDamageTick = Long.MIN_VALUE;
        public volatile long lastAllowFlightTick = Long.MIN_VALUE;
        // Last tick this player was in water or lava (see MovementGrace#WATER_GRACE_TICKS).
        public volatile long lastWaterTick = Long.MIN_VALUE;
        // Last tick this player was on a ladder, vines or scaffolding (see MovementGrace#CLIMB_GRACE_TICKS).
        public volatile long lastClimbTick = Long.MIN_VALUE;
        // Tick of the last updateMovementContextTracking run for this player; the watchdog restarts the per-player task when
        // this goes stale (terrain/effects would otherwise stay frozen at their defaults).
        public volatile long lastContextTick = Long.MIN_VALUE;
        // While now <= this tick, NoFall's peak height is pinned to the current Y instead of
        // accumulating a maximum (server teleport / game-mode change just happened).
        public volatile long peakResetUntilTick = Long.MIN_VALUE;
        // airborneStartTick as it was when the pending fall check STARTED - the live field can move
        // on (the player jumps again right after landing) before the check resolves a few ticks
        // later, which made a real damage event from the original fall look like it never fired.
        public volatile long fallCheckAirborneStartTick = 0;
        // Set the moment ANY tick of the current airborne stretch had a reason vanilla deals no fall
        // damage (invulnerable, in water, gliding, climbing, riding, flight, slow falling...). A
        // player can be protected mid-fall and on ordinary ground at the instant they land, so the
        // landing alone can't tell. Cleared at the real liftoff and on a server teleport.
        public volatile boolean airborneExempt = false;
        // The CONFIRMED (debounced) real-ground state as of the last updateMovementContextTracking
        // tick - used to detect the exact tick a real landing happens, independent of the
        // client-reported movement-packet flag or Player#isOnGround() (neither is trustworthy - see
        // AnticheatBukkitPlugin#isReallyOnSolidGround).
        public volatile boolean wasReallyOnGround = true;
        // Raw (non-debounced) real-block-below reading from the PREVIOUS tick - AnticheatBukkitPlugin
        // requires two consecutive raw-true readings before treating it as a genuine landing, so a
        // fast fall briefly brushing a ledge/overhang for exactly one tick can't register as a
        // spurious landing mid-fall.
        public volatile boolean rawOnGroundLastTick = false;
        // The tick this player's current airborne stretch began (last confirmed real liftoff) -
        // used to correlate against a real fall-damage event ANYWHERE during the whole stretch,
        // not just near the tick the real-block landing check happened to catch up. Confirmed live
        // that detection lag against a landing tick alone isn't reliable: a player landing on a
        // non-full-cube block (stairs/slab/fence, where the crude solid-block point-check takes far
        // longer to agree with vanilla's own collision than a flat full block does) showed the real
        // damage event firing 26 ticks (1.3s) before the landing was ever detected - a fixed few-
        // tick backward window missed it entirely, and both the real damage AND this check's own
        // corrective damage ended up applying.
        public volatile long airborneStartTick = 0;
        // A real landing with peakYSinceGrounded far enough above it to owe fall damage was just
        // observed - NoFall detection resolves this a few ticks later by checking whether vanilla's
        // OWN fall-damage system (see lastFallDamageEventTick below) engaged AT ALL for it, rather
        // than waiting on that event as the sole trigger (a NoFall hack can prevent it from firing
        // in the first place, by spoofing onGround in movement packets to reset vanilla's OWN
        // fall-distance tracking before impact - the same flag this class no longer trusts for
        // peakYSinceGrounded above).
        public volatile boolean fallCheckPending = false;
        public volatile double fallCheckDistance = 0.0;
        public volatile long fallCheckStartTick = 0;
        // Set by FallDamageEventListener the tick a real EntityDamageEvent(cause=FALL) fires for
        // this player - regardless of the resulting amount, even fully mitigated to near-zero by
        // armor/potions/absorption still counts, since the event firing at all means vanilla
        // correctly recognized and processed the fall. Comparing exact damage AMOUNTS instead (an
        // earlier version of this check did) kept producing false positives against every
        // legitimate way vanilla can reduce a hit: Protection, Resistance, Absorption, natural
        // regen landing in the same window, and probably others not yet hit in testing.
        public volatile long lastFallDamageEventTick = Long.MIN_VALUE;
    }

    /** The server's saved inventory is the baseline; existing items did not arrive through a new client action. */
    public void initializeOffhand(java.util.UUID player, boolean hasTotem) {
        OffhandTotemState state=offhandTotemState(player);
        state.legitimatelyEquipped=hasTotem;
        state.equippedSinceTick=hasTotem ? 0 : Long.MIN_VALUE;
    }

    public static final class OffhandTotemState {
        // The tick this player's offhand most recently changed FROM not-a-totem TO a totem, or
        // Long.MIN_VALUE if it doesn't currently hold one. Updated every tick (see
        // AnticheatBukkitPlugin#updateOffhandTotemTracking), which is also where legitimacy gets
        // decided - see legitimatelyEquipped.
        public volatile long equippedSinceTick = Long.MIN_VALUE;
        // Whether the totem CURRENTLY in the offhand (if any) was placed there via a real,
        // observed InventoryClickEvent or PlayerSwapHandItemsEvent - decided once, the tick it
        // appears, and carried forward until the slot empties again. Confirmed by direct
        // clarification: a hotbar-based swap (select a totem to the hotbar, press F), however
        // fast, is completely legitimate and NOT what this needs to catch - only a totem moving
        // out of inventory STORAGE into the hotbar/offhand is, and that's only possible through a
        // real click while the inventory screen is open. Timing was tried first and confirmed live
        // to not work: the actual cheat's restock delay varies from ~3 to 40+ seconds, well within
        // plausible human reaction range, so speed alone can't tell it apart from a real re-equip -
        // this checks HOW the item got there instead of how fast.
        public volatile boolean legitimatelyEquipped = false;
        // The tick of this player's most recent InventoryClickEvent or PlayerSwapHandItemsEvent -
        // correlated against equip time above to decide legitimatelyEquipped.
        public volatile long lastLegitimateActionTick = Long.MIN_VALUE;
    }

    /**
     * Backs a real-time "let them fall to the ground" correction, driven by
     * {@code AnticheatBukkitPlugin#updateFlyCorrectionTracking} - started the moment a movement
     * check (typically FlyA) confirms a violation via {@code CorrectPosition}, instead of the old
     * one-shot teleport-back-to-previous-position behavior, which just left a still-flying player
     * looking frozen in place rather than actually falling. Horizontal position freezes at wherever
     * the correction started; only Y is driven, using real vanilla gravity, until a solid block is
     * found underneath.
     */
    public static final class FlyCorrectionState {
        public volatile boolean active = false;
        public volatile double anchorX;
        public volatile double anchorZ;
        public volatile double currentY;
        public volatile double descentVelocity;
    }

    public TickSyncTracker tickSync(UUID id) {
        return tickSyncByPlayer.computeIfAbsent(id, k -> new TickSyncTracker());
    }

    public void trackEntityId(int entityId, UUID playerId) {
        playerIdByEntityId.put(entityId, playerId);
    }

    public UUID resolvePlayerId(int entityId) {
        return playerIdByEntityId.get(entityId);
    }

    public LastKnownPose lastKnownPose(UUID id) {
        return lastKnownPoseByPlayer.computeIfAbsent(id, k -> new LastKnownPose());
    }

    public OffhandTotemState offhandTotemState(UUID id) {
        return offhandTotemByPlayer.computeIfAbsent(id, k -> new OffhandTotemState());
    }

    public FlyCorrectionState flyCorrection(UUID id) {
        return flyCorrectionByPlayer.computeIfAbsent(id, k -> new FlyCorrectionState());
    }

    public void remove(UUID id) {
        tickSyncByPlayer.remove(id);
        playerIdByEntityId.values().remove(id);
        lastKnownPoseByPlayer.remove(id);
        offhandTotemByPlayer.remove(id);
        flyCorrectionByPlayer.remove(id);
    }
}
