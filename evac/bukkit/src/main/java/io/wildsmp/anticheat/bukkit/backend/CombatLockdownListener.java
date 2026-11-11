package io.wildsmp.anticheat.bukkit.backend;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;

/**
 * The fail-closed enforcement point: cancels attack packets outright while {@link
 * BackendConnectionState} says the backend has been unreachable past its grace period, per the
 * spec's chosen trade-off (never let cheaters play unmonitored, even at the cost of availability).
 * Runs at HIGH priority so it cancels before {@link
 * io.wildsmp.anticheat.bukkit.packet.AnticheatPacketListener} would otherwise forward the same
 * packet to a backend that isn't there to receive it anyway.
 */
public final class CombatLockdownListener extends PacketListenerAbstract {

    private final BackendConnectionState connectionState;
    private final java.util.function.BooleanSupplier authorized;

    public CombatLockdownListener(BackendConnectionState connectionState) {
        this(connectionState, () -> true);
    }

    public CombatLockdownListener(BackendConnectionState connectionState, java.util.function.BooleanSupplier authorized) {
        super(PacketListenerPriority.HIGH);
        this.connectionState = connectionState;
        this.authorized = authorized;
    }

    boolean shouldBlock(long now) {
        if (!authorized.getAsBoolean()) connectionState.onDisconnected(now);
        return connectionState.isFailedClosed(now);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (io.wildsmp.anticheat.bukkit.CombatExemptions.isExempt(event.getUser())) return;
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        if (!shouldBlock(System.currentTimeMillis())) {
            return;
        }
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        if (wrapper.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            event.setCancelled(true);
        }
    }
}
