package io.wildsmp.anticheat.bukkit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OffhandBaselineTest {
 @Test void savedOffhandTotemIsLegitimateWhenTrackingStarts() {
  PlayerDataManager data=new PlayerDataManager();UUID id=UUID.randomUUID();data.initializeOffhand(id,true);
  var state=data.offhandTotemState(id);assertTrue(state.legitimatelyEquipped);assertNotEquals(Long.MIN_VALUE,state.equippedSinceTick);
  data.initializeOffhand(id,false);assertFalse(state.legitimatelyEquipped);assertEquals(Long.MIN_VALUE,state.equippedSinceTick);
 }
}
