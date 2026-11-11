package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.MovementSample;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Flags trigger-bot cheats (violation type "TriggerBotA") - attacks firing the instant the
 * crosshair lines up with a target, with no real human reaction-time delay. See {@link
 * TriggerBotAnalyzer} for the actual timing analysis.
 *
 * <p>No deferred evaluation is needed - like {@link KillauraSilentAimCheck} and {@link
 * HitboxCheck}, this only ever looks at the attacker's own rotation history up to and including
 * the attack, never anything from ticks after it.
 */
public final class TriggerBotCheck extends Check {

    private static final String STATE_KEY = "TriggerBotCheck.State";
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;
    private static final Logger LOG = Logger.getLogger(TriggerBotCheck.class.getName());

    private final TriggerBotAnalyzer analyzer;
    private final Function<UUID, PlayerData> targetLookup;

    public TriggerBotCheck(Function<UUID, PlayerData> targetLookup) {
        this(new TriggerBotAnalyzer(), targetLookup);
    }

    public TriggerBotCheck(TriggerBotAnalyzer analyzer, Function<UUID, PlayerData> targetLookup) {
        super("TriggerBotA", CheckCategory.COMBAT);
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
        evaluate(data, last.tickTimestamp(), ap.targetId());
    }

    private void evaluate(PlayerData data, long attackTick, UUID targetId) {
        PlayerData targetData = targetLookup.apply(targetId);
        if (targetData == null) {
            LOG.log(Level.INFO, "TriggerBot: no PlayerData for target {0} - skipped this attack", targetId);
            return;
        }
        TickIndexedPositionHistory targetHistory = targetData.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        Optional<PositionSnapshot> targetSnapshot = targetHistory.at(attackTick);
        if (targetSnapshot.isEmpty()) {
            LOG.log(Level.INFO, "TriggerBot: no position history for target {0} at tick {1} - skipped this attack",
                    new Object[] {targetId, attackTick});
            return;
        }
        TriggerBotAnalyzer.State state = data.computeCheckState(STATE_KEY, TriggerBotAnalyzer.State::new);
        double score = analyzer.analyze(data.movementHistory(), attackTick, targetSnapshot.get(), state);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("trigger bot score %.2f", score));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data, long attackTick, UUID targetId) {
        evaluate(data, attackTick, targetId);
    }
}
