package io.wildsmp.anticheat.bukkit.backend;
import io.wildsmp.anticheat.core.check.CheckCategory;
public final class CombatPolicy {
    private CombatPolicy() {}
    public static boolean accepts(CheckCategory category, boolean approved) {
        return approved && category == CheckCategory.COMBAT;
    }
}
