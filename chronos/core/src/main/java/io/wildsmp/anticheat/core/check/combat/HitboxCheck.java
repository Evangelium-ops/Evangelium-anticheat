package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flags hitbox-expander cheats (violation type "Hitboxes") - hits registering even though the
 * attacker's aim ray never actually touches the target's real, vanilla-sized hitbox. See {@link
 * HitboxAnalyzer} for the actual ray-vs-box geometry.
 *
 * <p>Evaluation is deferred {@link #EVALUATION_DELAY_TICKS} ticks (like {@link KillauraCheck}) so
 * the aim samples AFTER the attack exist: the click lands between the last aim packet before it and
 * the next one after it, and judging only the stale earlier one made ordinary fast-flicking players
 * look like hitbox cheaters (18% of legit attacks "missed" in live data).
 */
public final class HitboxCheck extends Check {

    private static final String STATE_KEY = "HitboxCheck.State";
    private static final String PENDING_STATE_KEY = "HitboxCheck.PendingAttacks";
    static final int EVALUATION_DELAY_TICKS = 2;
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;
    private static final Logger LOG = Logger.getLogger(HitboxCheck.class.getName());

    private final HitboxAnalyzer analyzer;
    private final Function<UUID, PlayerData> targetLookup;

    public HitboxCheck(Function<UUID, PlayerData> targetLookup) {
        this(new HitboxAnalyzer(), targetLookup);
    }

    public HitboxCheck(HitboxAnalyzer analyzer, Function<UUID, PlayerData> targetLookup) {
        super("Hitboxes", CheckCategory.COMBAT);
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
        pendingAttacks(data).add(new PendingAttack(last.tickTimestamp(), ap.targetId()));
    }

    @Override
    public void onTick(PlayerData data) {
        MovementSample latest = data.movementHistory().peekLast();
        if (latest == null) {
            return;
        }
        long now = latest.tickTimestamp();
        Queue<PendingAttack> pending = pendingAttacks(data);
        PendingAttack attack;
        while ((attack = pending.peek()) != null && now - attack.tick() >= EVALUATION_DELAY_TICKS) {
            pending.poll();
            evaluate(data, attack.tick(), attack.targetId());
        }
    }

    private record PendingAttack(long tick, UUID targetId) {
    }

    private Queue<PendingAttack> pendingAttacks(PlayerData data) {
        return data.computeCheckState(PENDING_STATE_KEY, ConcurrentLinkedQueue::new);
    }

    private void evaluate(PlayerData data, long attackTick, UUID targetId) {
        PlayerData targetData = targetLookup.apply(targetId);
        if (targetData == null) {
            return;
        }
        TickIndexedPositionHistory targetHistory = targetData.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        Optional<PositionSnapshot> targetSnapshot = targetHistory.at(attackTick);
        if (targetSnapshot.isEmpty()) {
            return;
        }
        PositionSnapshot snapshot = targetSnapshot.get();
        // The target's real position at the click is within a tick of the backtracked one either way.
        List<PositionSnapshot> candidates = new ArrayList<>();
        candidates.add(snapshot);
        for (long offset : new long[] {-1L, 1L}) {
            targetHistory.at(attackTick + offset).filter(c -> !candidates.contains(c)).ifPresent(candidates::add);
        }
        LOG.log(Level.INFO, "Hitboxes: attack tick {0}, backtracked target snapshot tick {1} (offset {2}), "
                        + "target pos ({3}, {4}, {5})",
                new Object[] {attackTick, snapshot.tick(), snapshot.tick() - attackTick,
                        snapshot.x(), snapshot.y(), snapshot.z()});
        HitboxAnalyzer.State state = data.computeCheckState(STATE_KEY, HitboxAnalyzer.State::new);
        double score = analyzer.analyzeWithTolerance(data.movementHistory(), attackTick, candidates, state);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("hitbox score %.2f", score));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data, long attackTick, UUID targetId) {
        evaluate(data, attackTick, targetId);
    }
}
