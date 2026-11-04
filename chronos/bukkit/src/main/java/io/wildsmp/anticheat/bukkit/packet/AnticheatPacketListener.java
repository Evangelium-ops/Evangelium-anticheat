package io.wildsmp.anticheat.bukkit.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.bukkit.CombatExemptions;
import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import io.wildsmp.anticheat.core.check.combat.TickSyncTracker;

import java.util.UUID;

/**
 * The only place left that parses a real PacketEvents packet - translates it into a protocol
 * message and streams it to the backend, instead of dispatching into a local core PacketEventBus
 * the way the in-process design used to.
 */
public final class AnticheatPacketListener extends PacketListenerAbstract {

    private final PlayerDataManager playerDataManager;
    private final BackendClient backendClient;
    private final TickClock tickClock;
    private final java.util.function.Function<Integer, UUID> entityIdToPlayerId;

    public AnticheatPacketListener(PlayerDataManager playerDataManager, BackendClient backendClient,
                                    TickClock tickClock, java.util.function.Function<Integer, UUID> entityIdToPlayerId) {
        super(PacketListenerPriority.NORMAL);
        this.playerDataManager = playerDataManager;
        this.backendClient = backendClient;
        this.tickClock = tickClock;
        this.entityIdToPlayerId = entityIdToPlayerId;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        UUID playerId = user.getUUID();

        if (isMovementPacket(event)) {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
            long tick = tickClock.current();
            // Of the four "move player" packet variants, only some carry position and only some
            // carry rotation - PacketEvents reports the absent half as zero, not the player's real
            // last-known value. Merge in the cached half instead of trusting that zero, or a
            // rotation-only packet (say) would report the player standing at (0,0,0).
            PlayerDataManager.LastKnownPose pose = playerDataManager.lastKnownPose(playerId);
            double x = wrapper.hasPositionChanged() ? wrapper.getLocation().getX() : pose.x;
            double y = wrapper.hasPositionChanged() ? wrapper.getLocation().getY() : pose.y;
            double z = wrapper.hasPositionChanged() ? wrapper.getLocation().getZ() : pose.z;
            float yaw = wrapper.hasRotationChanged() ? wrapper.getLocation().getYaw() : pose.yaw;
            float pitch = wrapper.hasRotationChanged() ? wrapper.getLocation().getPitch() : pose.pitch;
            pose.x = x;
            pose.y = y;
            pose.z = z;
            pose.yaw = yaw;
            pose.pitch = pitch;
            // Tracks the current airborne stretch's peak height locally - see MaceDamageListener,
            // which needs a real-time fall distance to clamp an exploited hit before it applies,
            // with no backend round trip available synchronously inside a Bukkit event handler.
            // Deliberately NEVER reset off wrapper.isOnGround() - that's the client's own
            // self-reported flag, exactly what a NoFall hack fakes to reset the server's fall-
            // distance tracking before impact. Only accumulate the real peak here; the actual
            // reset happens in AnticheatBukkitPlugin#updateMovementContextTracking off
            // Player#isOnGround(), which is server-authoritative and can't be spoofed this way.
            pose.peakYSinceGrounded = io.wildsmp.anticheat.bukkit.MovementGrace.peakResetActive(tick, pose.peakResetUntilTick)
                    ? y
                    : Math.max(pose.peakYSinceGrounded, y);
            // terrain/effects are NOT resolved here - this handler runs on PacketEvents' Netty I/O
            // thread, and Paper throws on touching live Bukkit block/potion-effect state off the
            // main thread. AnticheatBukkitPlugin#updateMovementContextTracking refreshes pose.terrain/
            // pose.effects once per tick on the main thread instead; this just reads the cache.
            double estimatedTickLag = playerDataManager.tickSync(playerId).estimatedTickLag();
            backendClient.sendMovement(playerId, x, y, z, yaw, pitch, wrapper.isOnGround(), tick,
                    pose.terrain, pose.effects, estimatedTickLag);
            return;
        }

        if (CombatExemptions.isExempt(user)) return;

        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
            if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return;
            }
            UUID targetId = entityIdToPlayerId.apply(wrapper.getEntityId());
            if (targetId == null) {
                return; // target isn't a tracked player (e.g. hit a mob) - nothing to check
            }
            TickSyncTracker tickSync = playerDataManager.tickSync(playerId);
            long perceivedTick = tickSync.clientPerceivedTick(tickClock.current());
            backendClient.sendAttack(playerId, targetId, perceivedTick);
        }
    }

    private static boolean isMovementPacket(PacketReceiveEvent event) {
        var type = event.getPacketType();
        return type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || type == PacketType.Play.Client.PLAYER_ROTATION
                || type == PacketType.Play.Client.PLAYER_FLYING;
    }
}
