package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.Iterator;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flags "velocity" cheats (violation type "VelocityA") - a client reporting an impossible amount
 * of vertical knockback for itself after being hit. See {@link VelocityAnalyzer} for the physics.
 *
 * <p>Unlike every other combat check in this package, the flag lands on the VICTIM, not the
 * attacker - the cheat lives on the receiving end's own client (it decides how much velocity to
 * self-report after being hit), so that's whose behavior is actually suspicious here. Dispatch
 * still starts from the attacker's {@link #onPacketReceive}/{@link #onTick} (that's where {@link
 * AttackPacket}s arrive), but {@link #evaluate} looks up and flags the target's own {@code
 * PlayerData} instead of the attacker's.
 *
 * <p>Evaluation can't happen on a fixed delay like {@link KillauraCheck}'s pending-attack queue -
 * {@link VelocityAnalyzer} needs to watch the victim's FULL post-hit arc (however long it takes
 * them to land again, confirmed live to often run 15-20+ ticks), not just a snapshot a couple of
 * ticks later. So each pending hit is re-checked every tick until {@link VelocityAnalyzer#analyze}
 * reports it's no longer {@link VelocityAnalyzer#UNRESOLVED}, then removed from the queue.
 */
public final class VelocityCheck extends Check {

    private static final String STATE_KEY = "VelocityCheck.State";
    private static final String PENDING_STATE_KEY = "VelocityCheck.Pending";
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;
    private static final Logger LOG = Logger.getLogger(VelocityCheck.class.getName());

    private record PendingHit(long tick, UUID targetId) {
    }

    private final VelocityAnalyzer analyzer;
    private final Function<UUID, PlayerData> targetLookup;

    public VelocityCheck(Function<UUID, PlayerData> targetLookup) {
        this(new VelocityAnalyzer(), targetLookup);
    }

    public VelocityCheck(VelocityAnalyzer analyzer, Function<UUID, PlayerData> targetLookup) {
        super("VelocityA", CheckCategory.COMBAT);
        this.analyzer = analyzer;
        this.targetLookup = targetLookup;
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof AttackPacket ap)) {
            return;
        }
        MovementSample last = data.movementHistory().peekLast();
        if (last == null) {
            return;
        }
        pendingHits(data).add(new PendingHit(last.tickTimestamp(), ap.targetId()));
    }

    @Override
    public void onTick(PlayerData data) {
        Queue<PendingHit> pending = pendingHits(data);
        if (pending.isEmpty()) {
            return;
        }
        Iterator<PendingHit> it = pending.iterator();
        while (it.hasNext()) {
            PendingHit hit = it.next();
            if (evaluate(hit.tick(), hit.targetId())) {
                it.remove();
            }
        }
    }

    /** @return true once this hit's arc has resolved (landed or timed out) and should leave the pending queue. */
    private boolean evaluate(long hitTick, UUID targetId) {
        PlayerData targetData = targetLookup.apply(targetId);
        if (targetData == null) {
            LOG.log(Level.INFO, "Velocity: no PlayerData for target {0} - dropped this hit", targetId);
            return true;
        }
        VelocityAnalyzer.State state = targetData.computeCheckState(STATE_KEY, VelocityAnalyzer.State::new);
        double score = analyzer.analyze(targetData.movementHistory(), hitTick, state);
        if (Double.isNaN(score)) {
            return false; // still watching the arc unfold
        }
        if (score > SUSPICION_THRESHOLD) {
            flag(targetData, score * VL_SCALE, String.format("velocity score %.2f", score));
        }
        return true;
    }

    private Queue<PendingHit> pendingHits(PlayerData data) {
        return data.computeCheckState(PENDING_STATE_KEY, ConcurrentLinkedQueue::new);
    }

    /** Test-only hook - real dispatch is onPacketReceive (queues) followed by repeated onTick polling. */
    boolean testEvaluate(long hitTick, UUID targetId) {
        return evaluate(hitTick, targetId);
    }
}
