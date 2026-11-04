package io.wildsmp.anticheat.bukkit.backend;
import io.wildsmp.anticheat.core.check.CheckCategory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CombatPolicyTest {
 @Test void onlyApprovedCombatVerdictsAreEnforced() {
  assertTrue(CombatPolicy.accepts(CheckCategory.COMBAT, true));
  assertFalse(CombatPolicy.accepts(CheckCategory.COMBAT, false));
  assertFalse(CombatPolicy.accepts(CheckCategory.MOVEMENT, true));
  assertFalse(CombatPolicy.accepts(CheckCategory.MOVEMENT, false));
 }
}
