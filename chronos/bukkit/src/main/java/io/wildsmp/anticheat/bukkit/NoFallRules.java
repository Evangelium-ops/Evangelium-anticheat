package io.wildsmp.anticheat.bukkit;

/**
 * Pure NoFall bookkeeping rules, kept free of Bukkit types so they can be unit tested.
 */
public final class NoFallRules {

    private NoFallRules() {
    }

    /**
     * Blocks the player lands in/on, or moves through, that make vanilla deal NO fall damage and fire
     * NO damage event at all - so "no fall damage happened" is not evidence of a hack there. Matched by
     * {@code Material#name()} to stay free of Bukkit types.
     */
    public static boolean negatesFallDamage(String materialName) {
        return switch (materialName) {
            case "WATER", "LAVA", "BUBBLE_COLUMN", "COBWEB", "POWDER_SNOW", "SCAFFOLDING", "SWEET_BERRY_BUSH",
                 "LADDER", "VINE", "TWISTING_VINES", "TWISTING_VINES_PLANT", "WEEPING_VINES", "WEEPING_VINES_PLANT",
                 "CAVE_VINES", "CAVE_VINES_PLANT", "SLIME_BLOCK", "HONEY_BLOCK", "HAY_BLOCK" -> true;
            default -> materialName.endsWith("_BED");
        };
    }

    /**
     * Vanilla computes damage as ceil(fallDistance - 3 - jumpBoostLevel), so each Jump Boost level
     * (level = amplifier + 1) lengthens the damage-free fall by a block.
     */
    public static double safeDistance(double baseSafeDistance, int jumpBoostLevel) {
        return baseSafeDistance + Math.max(0, jumpBoostLevel);
    }

    /**
     * Vanilla ignores fall damage that is not larger than damage taken within the last few ticks
     * (invulnerability frames), without firing a damage event for it.
     */
    public static boolean inDamageImmunityFrames(int noDamageTicks, int maximumNoDamageTicks) {
        return noDamageTicks > maximumNoDamageTicks / 2;
    }

    /**
     * Whether a single tick of real ground contact should restart the airborne peak right away,
     * without waiting for the debounced two-tick "genuine landing" confirmation.
     *
     * <p>The debounce exists so a long fall brushing a ledge for one tick can't register as a landing
     * mid-fall. But it also meant walking DOWN a staircase of blocks quickly (each step only touches
     * the ground for a tick or so) never confirmed the intermediate landings, so the peak kept
     * accumulating across steps - four 1-block steps read as one 4-block fall that vanilla, which saw
     * each step as a safe landing, never damaged (confirmed live). A drop within the safe distance
     * can never owe fall damage, so restarting the peak after one loses nothing the check could ever
     * flag; a drop beyond it keeps the stricter debounced handling.
     */
    public static boolean shouldResetPeakOnGroundContact(double peakY, double currentY, double safeDistance) {
        return peakY - currentY <= safeDistance;
    }
}
