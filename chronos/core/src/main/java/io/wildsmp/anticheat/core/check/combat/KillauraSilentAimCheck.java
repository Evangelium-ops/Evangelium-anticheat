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

/**
 * Flags "silent aim" (violation type "KillauraS") - a hit registering while the attacker's actual
 * crosshair/rotation never pointed anywhere near the target at all. A separate check/violation
 * type from {@link KillauraCheck}'s "KillauraA" since it's a structurally different tell:
 * KillauraA's signals both look for a suspicious CORRELATION between the attacker's aim and the
 * target (a snap, a too-precise correction) - this looks for the opposite, aim that never
 * correlates with the target at all. See {@link SilentAimAnalyzer} for the actual detection
 * logic.
 *
 * <p>Unlike KillauraCheck, no deferred evaluation is needed - this only ever looks at the
 * attacker's own aim at the moment of the attack, never anything from ticks after it.
 */
public final class KillauraSilentAimCheck extends Check {

    private static final String STATE_KEY = "KillauraSilentAimCheck.State";
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;

    private final SilentAimAnalyzer analyzer;
    private final Function<UUID, PlayerData> targetLookup;

    public KillauraSilentAimCheck(Function<UUID, PlayerData> targetLookup) {
        this(new SilentAimAnalyzer(), targetLookup);
    }

    public KillauraSilentAimCheck(SilentAimAnalyzer analyzer, Function<UUID, PlayerData> targetLookup) {
        super("KillauraS", CheckCategory.COMBAT);
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
            return;
        }
        TickIndexedPositionHistory targetHistory = targetData.computeCheckState(
                PositionHistoryCheck.HISTORY_STATE_KEY, TickIndexedPositionHistory::new);
        Optional<PositionSnapshot> targetSnapshot = targetHistory.at(attackTick);
        if (targetSnapshot.isEmpty()) {
            return;
        }
        SilentAimAnalyzer.State state = data.computeCheckState(STATE_KEY, SilentAimAnalyzer.State::new);
        double score = analyzer.analyze(data.movementHistory(), attackTick, targetSnapshot.get(), state);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("silent aim score %.2f", score));
        }
    }

    /** Test-only hook - real dispatch goes through {@link #onPacketReceive}. */
    void testEvaluate(PlayerData data, long attackTick, UUID targetId) {
        evaluate(data, attackTick, targetId);
    }
}
