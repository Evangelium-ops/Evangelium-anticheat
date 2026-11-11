package io.wildsmp.anticheat.core.check.combat;

import io.wildsmp.anticheat.core.check.Check;
import io.wildsmp.anticheat.core.check.CheckCategory;
import io.wildsmp.anticheat.core.packet.AutoTotemPacket;
import io.wildsmp.anticheat.core.packet.InboundPacket;
import io.wildsmp.anticheat.core.player.PlayerData;

/**
 * Flags "auto totem" cheats (violation type "AutoTotemA") - an offhand Totem of Undying that
 * appeared with no legitimate cause behind it.
 *
 * <p>Unlike every other check in this package, there's no threshold or analysis to apply here -
 * bukkit-side ({@code AutoTotemListener}) already confirmed no real {@code InventoryClickEvent} or
 * {@code PlayerSwapHandItemsEvent} explains the totem before this packet is ever sent (see {@link
 * AutoTotemPacket}), so its mere existence already IS the violation. Bukkit also prevents the
 * exploit's effect in real time when it detects this (strips the totem, or cancels the resurrect) -
 * this check's job is purely to make sure the attempt still gets flagged and tracked through the
 * normal violation pipeline, independent of whether that real-time prevention already stopped it.
 */
public final class AutoTotemCheck extends Check {

    // Higher than the fraction-based checks in this package - a single confirmed instance here is
    // already unambiguous evidence, not one data point among several needed to trust a pattern.
    private static final double VL_SCALE = 10.0;

    public AutoTotemCheck() {
        super("AutoTotemA", CheckCategory.COMBAT);
    }

    @Override
    public void onPacketReceive(PlayerData data, InboundPacket packet) {
        if (!(packet instanceof AutoTotemPacket)) {
            return;
        }
        flag(data, VL_SCALE, "offhand totem appeared with no corresponding inventory click or hand-swap event");
    }
}
