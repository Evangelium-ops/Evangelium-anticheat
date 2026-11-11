package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flags an attack whose aim around it looks faked rather than genuine human tracking, via three
 * independent signals: {@link RotationTrendAnalyzer} (a snap that immediately reverts, or the
 * same snap magnitude recurring with suspicious precision - the signature of a bot that doesn't
 * need to keep tracking once it's locked on), {@link AimPrecisionAnalyzer} (a meaningful
 * correction landing on the target with a precision no human hand reproduces - the signature of
 * aim assist, which typically never needs to revert at all since it just computes the exact
 * correction and applies it), and {@link FrozenAimAnalyzer} (the attacker's own camera not moving
 * at all for a real span of active combat - confirmed live to catch an assist the first two
 * signals both missed, since neither one looks at the attacker's rotation independent of the
 * target at all). See {@link KillauraSilentAimCheck} (violation type "KillauraS") for a fourth,
 * structurally different case: aim that never correlates with the target at all.
 *
 * <p>Evaluation is deliberately deferred, not run synchronously when the attack packet arrives:
 * {@link RotationTrendAnalyzer} needs rotation samples from *after* the attack tick to detect a
 * snap-then-revert pattern, and those samples don't exist yet at the moment the attack packet is
 * processed - they're still future ticks. {@link #onPacketReceive} just queues the attack; {@link
 * #onTick} evaluates each queued attack once enough ticks have passed for the "after" window
 * {@link RotationTrendAnalyzer} needs to actually be populated.
 */
public final class KillauraCheck extends Check {

    private static final String STATE_KEY = "KillauraCheck.RotationState";
    private static final String AIM_STATE_KEY = "KillauraCheck.AimPrecisionState";
    private static final String FROZEN_STATE_KEY = "KillauraCheck.FrozenAimState";
    private static final String PENDING_STATE_KEY = "KillauraCheck.PendingAttacks";
    private static final String RECENT_ATTACK_TICKS_STATE_KEY = "KillauraCheck.RecentAttackTicks";
    private static final String SUSPICIOUS_ATTACKS_STATE_KEY = "KillauraCheck.SuspiciousAttacks";
    // A single flick-and-return is ordinary human aim (target switching, overshoot correction), so
    // one suspicious attack must never flag on its own - confirmed live, real players tripped it
    // constantly. An aimbot's signature repeats attack after attack; require it to.
    private static final int REPEAT_HISTORY_ATTACKS = 8;
    private static final int REPEAT_MIN_ATTACKS_SEEN = 4;
    private static final int REPEAT_MIN_SUSPICIOUS = 3;
    private static final int EVALUATION_DELAY_TICKS = 3; // >= RotationTrendAnalyzer's lookahead window
    // How long after an attack to still count it toward "actively engaged," for continuous
    // frozen-aim sampling - an aim-assist locked onto a moving target doesn't land a hit on every
    // single tick, so limiting that signal to only the exact ticks a hit connects throws away most
    // of the evidence.
    private static final long RECENT_COMBAT_WINDOW_TICKS = 100L; // 5s at 20 ticks/sec
    // A single isolated hit followed by the player simply not touching their mouse for the next 5
    // seconds is completely ordinary - real players hold still between actions constantly. Requiring
    // at least this many separate attacks within the window is what actually distinguishes sustained
    // engagement (worth continuously tracking) from one attack followed by normal idle stillness.
    private static final int MIN_RECENT_ATTACKS_FOR_CONTINUOUS_TRACKING = 2;
    // Same raycast distance bound HitboxAnalyzer and TriggerBotAnalyzer already use (ReachCheck's
    // own live-tuned tolerance) - but NOT their tight padding. Confirmed live: reusing
    // ReachCheck.EPSILON (~0.3 blocks) here made "tracking required" flicker true/false almost
    // every single tick during an ordinary stationary combo, because the target was sitting right
    // at that padded boundary and normal knockback-settling jitter kept crossing it. Those other
    // checks need a tight, precise raycast (they're asking "did this exact hit register against
    // the real hitbox"); this one is only asking the much coarser "did the target move enough to
    // plausibly need tracking," so a generous padding is not just safe here, it's correct - real
    // evasive movement clears it by blocks, not fractions of one.
    private static final double MAX_RAY_DISTANCE_BLOCKS = ReachCheck.VANILLA_MAX_REACH + ReachCheck.EPSILON;
    private static final double TRACKING_COVERAGE_PADDING_BLOCKS = 1.5;
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;
    private static final Logger LOG = Logger.getLogger(KillauraCheck.class.getName());

    private record PendingAttack(long tick, UUID targetId) {
    }

    private final RotationTrendAnalyzer analyzer;
    private final AimPrecisionAnalyzer aimAnalyzer;
    private final FrozenAimAnalyzer frozenAimAnalyzer;
    private final Function<UUID, PlayerData> targetLookup;

    public KillauraCheck(Function<UUID, PlayerData> targetLookup) {
        this(new RotationTrendAnalyzer(), new AimPrecisionAnalyzer(), new FrozenAimAnalyzer(), targetLookup);
    }

    public KillauraCheck(RotationTrendAnalyzer analyzer, AimPrecisionAnalyzer aimAnalyzer,
                          FrozenAimAnalyzer frozenAimAnalyzer, Function<UUID, PlayerData> targetLookup) {
        super("KillauraA", CheckCategory.COMBAT);
        this.analyzer = analyzer;
        this.aimAnalyzer = aimAnalyzer;
        this.frozenAimAnalyzer = frozenAimAnalyzer;
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
        PendingAttack recorded = new PendingAttack(last.tickTimestamp(), ap.targetId());
        pendingAttacks(data).add(recorded);
        recentAttacks(data).addLast(recorded);
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
            // If another attack is already queued close behind this one (a combo), its own
            // tracking correction would otherwise land inside this attack's lookahead window and
            // get misread as "the aim reverted" - bound the window at that next attack's tick.
            PendingAttack next = pending.peek();
            long nextAttackTick = next == null ? Long.MAX_VALUE : next.tick();
            evaluate(data, attack.tick(), nextAttackTick, attack.targetId());
        }

        // Keep sampling frozen-aim on every tick while the player is in *sustained* active
        // engagement - not just on the exact ticks a hit lands (an assist locked onto a moving
        // target doesn't land a hit on every tick) but also not off a single isolated attack (a
        // hit followed by the player simply not touching their mouse for the next few seconds is
        // completely ordinary, not evidence of anything).
        Deque<PendingAttack> recentAttacks = recentAttacks(data);
        while (!recentAttacks.isEmpty() && now - recentAttacks.peekFirst().tick() > RECENT_COMBAT_WINDOW_TICKS) {
            recentAttacks.removeFirst();
        }
        if (recentAttacks.size() >= MIN_RECENT_ATTACKS_FOR_CONTINUOUS_TRACKING) {
            boolean trackingWasRequired = trackingWasRequired(data, recentAttacks.peekLast().targetId(), now);
            FrozenAimAnalyzer.State frozenState = data.computeCheckState(FROZEN_STATE_KEY, FrozenAimAnalyzer.State::new);
            double frozenScore = frozenAimAnalyzer.analyze(data.movementHistory(), now, trackingWasRequired, frozenState);
            LOG.log(Level.INFO, "KillauraA continuous: attacker {0} at tick {1} - tracking required {2}, frozen score {3}",
                    new Object[] {data.id(), now, trackingWasRequired, frozenScore});
            if (frozenScore > SUSPICION_THRESHOLD) {
                flag(data, frozenScore * VL_SCALE, String.format("frozen aim score %.2f (continuous)", frozenScore));
            }
        } else {
            // Out of sustained combat: forget any frozen-camera streak so it can't carry over and
            // fire the instant the next fight starts (the logs showed a stale 0.875 doing exactly that).
            data.computeCheckState(FROZEN_STATE_KEY, FrozenAimAnalyzer.State::new).reset();
        }
    }

    /**
     * True if the target genuinely moved out of (or into) the attacker's own current, frozen aim
     * direction's real hitbox coverage between the start and end of the window FrozenAimAnalyzer
     * checks the camera against - see {@link AimGeometry#requiredTrackingAcrossWindow} for why this
     * ray-vs-hitbox coverage flip, not raw distance or a center-point angle, is the right signal:
     * it's immune to ordinary vertical wobble (melee knockback, jump-critting) on either player's
     * side, which swings a target's exact center point around a lot without ever actually taking it
     * out of where a frozen aim already covers.
     *
     * <p>The attacker's own position is held FIXED (at their position "now"), not their real
     * position at each end of the window - otherwise the attacker's own jump-crit bobbing would
     * shift the ray's origin and get misread as target movement.
     */
    private boolean trackingWasRequired(PlayerData data, UUID targetId, long now) {
        PlayerData targetData = targetLookup.apply(targetId);
        if (targetData == null) {
            return false;
        }
        List<MovementSample> attackerHistory = List.copyOf(data.movementHistory());
        int nowIndex = AimGeometry.indexOfClosest(attackerHistory, now);
        if (nowIndex < 0) {
            return false;
        }
        MovementSample attackerNow = attackerHistory.get(nowIndex);

        TickIndexedPositionHistory targetHistory = targetData.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        Optional<PositionSnapshot> targetStart = targetHistory.at(now - FrozenAimAnalyzer.WINDOW_TICKS);
        Optional<PositionSnapshot> targetEnd = targetHistory.at(now);
        if (targetStart.isEmpty() || targetEnd.isEmpty()) {
            return false;
        }
        return AimGeometry.requiredTrackingAcrossWindow(attackerNow, targetStart.get(), targetEnd.get(),
                MAX_RAY_DISTANCE_BLOCKS, TRACKING_COVERAGE_PADDING_BLOCKS);
    }

    private Queue<PendingAttack> pendingAttacks(PlayerData data) {
        return data.computeCheckState(PENDING_STATE_KEY, ConcurrentLinkedQueue::new);
    }

    private Deque<PendingAttack> recentAttacks(PlayerData data) {
        return data.computeCheckState(RECENT_ATTACK_TICKS_STATE_KEY, ArrayDeque::new);
    }

    private void evaluate(PlayerData data, long attackTick, long nextAttackTick, UUID targetId) {
        RotationTrendAnalyzer.State state = data.computeCheckState(STATE_KEY, RotationTrendAnalyzer.State::new);
        double trendScore = analyzer.analyze(data.movementHistory(), attackTick, nextAttackTick, state);

        double precisionScore = 0.0;
        PlayerData targetData = targetLookup.apply(targetId);
        if (targetData == null) {
            LOG.log(Level.INFO, "KillauraA: no PlayerData for target {0} - aim precision skipped this attack", targetId);
        } else {
            TickIndexedPositionHistory targetHistory = targetData.computeCheckState(
                    PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
            Optional<PositionSnapshot> targetSnapshot = targetHistory.at(attackTick);
            if (targetSnapshot.isEmpty()) {
                LOG.log(Level.INFO, "KillauraA: no position history for target {0} at tick {1} - aim precision skipped this attack",
                        new Object[] {targetId, attackTick});
            } else {
                AimPrecisionAnalyzer.State aimState = data.computeCheckState(AIM_STATE_KEY, AimPrecisionAnalyzer.State::new);
                precisionScore = aimAnalyzer.analyze(data.movementHistory(), attackTick, targetSnapshot.get(), aimState);
            }
        }

        // Frozen-aim is tracked continuously from onTick instead (see there) - it doesn't need a
        // specific attack's target at all, and sampling it here too would double-count whichever
        // tick happens to coincide with a landed hit.
        double score = trendScore + precisionScore;
        LOG.log(Level.INFO, "KillauraA: evaluated attack from {0} - rotation trend {1}, aim precision {2}, total {3}",
                new Object[] {data.id(), trendScore, precisionScore, score});
        boolean suspicious = score > SUSPICION_THRESHOLD;
        Deque<Boolean> recentVerdicts = data.computeCheckState(SUSPICIOUS_ATTACKS_STATE_KEY, ArrayDeque::new);
        recentVerdicts.addLast(suspicious);
        while (recentVerdicts.size() > REPEAT_HISTORY_ATTACKS) {
            recentVerdicts.removeFirst();
        }
        long suspiciousCount = recentVerdicts.stream().filter(Boolean::booleanValue).count();
        if (suspicious && recentVerdicts.size() >= REPEAT_MIN_ATTACKS_SEEN && suspiciousCount >= REPEAT_MIN_SUSPICIOUS) {
            flag(data, score * VL_SCALE, String.format(
                    "rotation trend score %.2f, aim precision score %.2f (%d of last %d attacks)",
                    trendScore, precisionScore, suspiciousCount, recentVerdicts.size()));
        }
    }

    /** Test-only hook - real dispatch is onPacketReceive (queues) followed by onTick (evaluates). */
    void testEvaluate(PlayerData data, long attackTick, UUID targetId) {
        evaluate(data, attackTick, Long.MAX_VALUE, targetId);
    }
}
