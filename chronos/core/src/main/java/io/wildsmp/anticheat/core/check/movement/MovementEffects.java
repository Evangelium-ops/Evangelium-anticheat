package io.wildsmp.anticheat.core.check.movement;

/**
 * The handful of potion effects/player states that change the per-tick movement ceiling - computed
 * bukkit-side, once per movement packet, from data already available locally (active potion
 * effects, sprint/sneak/swim/glide state).
 */
public record MovementEffects(int speedLevel, int slownessLevel, int jumpBoostLevel, boolean levitation,
                               boolean slowFalling, boolean sprinting, boolean sneaking, boolean swimming,
                               boolean gliding, boolean flightAllowed, boolean recentlyLaunched) {

    public static MovementEffects none() {
        return new MovementEffects(0, 0, 0, false, false, false, false, false, false, false, false);
    }
}
