package ac.grim.grimac.utils.reflection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ACModeTest {
 @Test void unifiedModeLeavesMovementAndProtocolSafetyToGrim() {
  assertFalse(ACMode.grimCheckEnabled(true, "ac.grim.grimac.checks.impl.combat.Reach"));
  assertFalse(ACMode.grimCheckEnabled(true, "ac.grim.grimac.checks.impl.aim.AimModulo360"));
  assertTrue(ACMode.grimCheckEnabled(true, "ac.grim.grimac.checks.impl.prediction.GroundSpoof"));
  assertTrue(ACMode.grimCheckEnabled(true, "ac.grim.grimac.checks.impl.timer.Timer"));
  assertTrue(ACMode.grimCheckEnabled(true, "ac.grim.grimac.checks.impl.misc.Post"));
  assertTrue(ACMode.grimCheckEnabled(false, "ac.grim.grimac.checks.impl.combat.Reach"));
 }
}
