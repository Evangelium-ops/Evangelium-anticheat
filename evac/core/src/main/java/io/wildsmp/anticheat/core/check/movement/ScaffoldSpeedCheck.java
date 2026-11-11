package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.BlockPlacePacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.PlayerData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Flags a sustained block-placement rate beyond realistic human capability while bridging - mirrors
 * ClickSpeedCheck's exact reasoning for attacks: a macro-driven scaffold client can sustain a
 * placement rate no human hand does, since nothing about vanilla's own placement handling stops the
 * client from SENDING placements that fast (there's no hard cooldown the way attack damage has
 * one). Rotation/angle-independent, so it catches a scaffold module that has otherwise learned to
 * fake plausible look-alignment (see {@link ScaffoldAnalyzer}) but still places faster than a human
 * hand can click.
 */
public final class ScaffoldSpeedCheck extends Check {

    private static final String STATE_KEY = "ScaffoldSpeedCheck.RecentPlacementTicks";
    private static final int WINDOW_SIZE = 15;
    private static final int MIN_SAMPLES = 10;
    private static final double TICKS_PER_SECOND = 20.0;
    // A human bridging while walking realistically tops out well under this, even in a short burst
    // - placing requires aiming down at your feet each time, unlike an already-targeted attack.
    // Generous on purpose - unverified against real playtest data yet, may need retuning the way
    // ClickSpeedA's did.
    private static final double SUSPICIOUS_SUSTAINED_PLACEMENTS_PER_SECOND = 10.0;
    private static final double SUSPICION_THRESHOLD = 0.3;
    private static final double VL_SCALE = 5.0;

    public ScaffoldSpeedCheck() {
        super("ScaffoldSpeedA", CheckCategory.MOVEMENT);
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof BlockPlacePacket bpp)) {
            return;
        }
        Deque<Long> recent = data.computeCheckState(STATE_KEY, ArrayDeque::new);
        recent.addLast(bpp.tick());
        while (recent.size() > WINDOW_SIZE) {
            recent.removeFirst();
        }
        evaluate(data, recent);
    }

    private void evaluate(PlayerData data, Deque<Long> recent) {
        if (recent.size() < MIN_SAMPLES) {
            return; // not enough placements yet to tell a sustained rate from a short lucky burst
        }
        List<Long> ordered = List.copyOf(recent);
        long spanTicks = ordered.get(ordered.size() - 1) - ordered.get(0);
        if (spanTicks <= 0) {
            return; // degenerate - all samples landed on the same tick, can't measure a rate
        }
        double spanSeconds = spanTicks / TICKS_PER_SECOND;
        double placementsPerSecond = (ordered.size() - 1) / spanSeconds;

        if (placementsPerSecond <= SUSPICIOUS_SUSTAINED_PLACEMENTS_PER_SECOND) {
            return;
        }
        double score = Math.min(1.0,
                (placementsPerSecond - SUSPICIOUS_SUSTAINED_PLACEMENTS_PER_SECOND) / SUSPICIOUS_SUSTAINED_PLACEMENTS_PER_SECOND);
        if (score > SUSPICION_THRESHOLD) {
            flag(data, score * VL_SCALE, String.format("%.1f sustained placements/sec", placementsPerSecond));
        }
    }
}
