package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AttackPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Flags a sustained click rate beyond realistic human capability - a rotation/aim-independent
 * signal, so it catches macro/autoclicker-driven attacks (including "multi-aura" style clients)
 * that {@link KillauraCheck}'s aim-based signals wouldn't, since nothing about clicking fast
 * requires suspicious aim at all.
 *
 * <p>Vanilla's attack-cooldown mechanic only reduces damage on a too-fast follow-up hit - it
 * doesn't stop the client from sending attack packets faster than that, so a macro can genuinely
 * sustain a rate no human hand does. A short jitter/butterfly-click burst is real and legitimate
 * (competitive clickers can hit ~15-20 CPS briefly); a *sustained* rate this high across many
 * consecutive attacks isn't.
 */
public final class ClickSpeedCheck extends Check {

    private static final String STATE_KEY = "ClickSpeedCheck.RecentAttackTicks";
    private static final int WINDOW_SIZE = 15;
    private static final int MIN_SAMPLES = 10;
    private static final double TICKS_PER_SECOND = 20.0;
    // Elite competitive jitter/butterfly clicking peaks around 15-20 CPS in short bursts; sustained
    // across 10+ consecutive attacks, this is beyond realistic manual capability. Generous on
    // purpose - unverified against real playtest data yet, may need retuning like ReachA's did.
    private static final double SUSPICIOUS_SUSTAINED_CPS = 20.0;
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;

    public ClickSpeedCheck() {
        super("ClickSpeedA", CheckCategory.COMBAT);
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof AttackPacket ap)) {
            return;
        }
        Deque<Long> recent = data.computeCheckState(STATE_KEY, ArrayDeque::new);
        recent.addLast(ap.perceivedTick());
        while (recent.size() > WINDOW_SIZE) {
            recent.removeFirst();
        }
        evaluate(data, recent);
    }

    private void evaluate(PlayerData data, Deque<Long> recent) {
        if (recent.size() < MIN_SAMPLES) {
            return; // not enough attacks yet to tell a sustained rate from a short lucky burst
        }
        List<Long> ordered = List.copyOf(recent);
        long spanTicks = ordered.get(ordered.size() - 1) - ordered.get(0);
        if (spanTicks <= 0) {
            return; // degenerate - all samples landed on the same tick, can't measure a rate
        }
        double spanSeconds = spanTicks / TICKS_PER_SECOND;
        double cps = (ordered.size() - 1) / spanSeconds;

        if (cps <= SUSPICIOUS_SUSTAINED_CPS) {
            return;
        }
        double score = Math.min(1.0, (cps - SUSPICIOUS_SUSTAINED_CPS) / SUSPICIOUS_SUSTAINED_CPS);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("%.1f sustained clicks/sec", cps));
        }
    }
}
