package io.wildsmp.anticheat.core.check.movement;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.FallDamagePacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.PlayerData;

public final class NoFallCheck extends Check {

    // Matches AutoTotemCheck/MaceDamageCheck's convention for "bukkit's report IS the evidence, no
    // threshold left to apply" checks: scaled to the default violation threshold (10.0) itself, so
    // a single confirmed catch produces a verdict immediately rather than silently sitting under
    // the threshold until a second one arrives (or decays away before it does).
    private static final double VL_SCALE = 10.0;

    public NoFallCheck() {
        super("NoFallA", CheckCategory.MOVEMENT);
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof FallDamagePacket fdp)) {
            return;
        }
        flag(data, VL_SCALE, String.format(
                "fell %.1f blocks (past the %.1f-block safe distance) with no fall damage applied",
                fdp.trackedFallDistance(), VanillaPhysics.FALL_DAMAGE_SAFE_DISTANCE_BLOCKS));
    }
}
