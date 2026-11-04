package io.wildsmp.anticheat.bukkit.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import io.wildsmp.anticheat.bukkit.PlayerDataManager;
import io.wildsmp.anticheat.core.check.combat.TickClock;
import io.wildsmp.anticheat.core.check.combat.TickSyncTracker;

/**
 * Receives the client's acknowledgment of the transaction packets {@link
 * io.wildsmp.anticheat.bukkit.AnticheatBukkitPlugin} sends on a repeating timer, feeding that
 * player's {@link TickSyncTracker} in {@link PlayerDataManager} - purely local bookkeeping, never
 * sent to the backend (see {@link PlayerDataManager}'s class doc).
 */
public final class TransactionSyncListener extends PacketListenerAbstract {

    private final PlayerDataManager playerDataManager;
    private final TickClock tickClock;

    public TransactionSyncListener(PlayerDataManager playerDataManager, TickClock tickClock) {
        super(PacketListenerPriority.MONITOR);
        this.playerDataManager = playerDataManager;
        this.tickClock = tickClock;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PONG) {
            return;
        }
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        WrapperPlayClientPong wrapper = new WrapperPlayClientPong(event);
        TickSyncTracker tracker = playerDataManager.tickSync(user.getUUID());
        tracker.onTransactionAcknowledged(wrapper.getId(), tickClock.current());
    }
}
