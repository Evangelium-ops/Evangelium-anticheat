package io.wildsmp.anticheat.bukkit.backend;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
class CombatLockdownLeaseTest {
 @Test void silentOpenStreamCannotKeepCombatEnabledAfterItsLicenseLeaseExpires(){
  BackendConnectionState state=new BackendConnectionState(5000);AtomicBoolean authorized=new AtomicBoolean(true);
  CombatLockdownListener listener=new CombatLockdownListener(state,authorized::get);
  assertFalse(listener.shouldBlock(100));authorized.set(false);
  assertFalse(listener.shouldBlock(200));assertTrue(listener.shouldBlock(5201));
  authorized.set(true);state.onConnected();assertFalse(listener.shouldBlock(5300));
 }
}
