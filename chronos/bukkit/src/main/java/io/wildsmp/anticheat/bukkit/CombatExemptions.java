package io.wildsmp.anticheat.bukkit;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import java.util.UUID;
/** Bedrock positions still reach the backend as target history, but their own Java combat checks are skipped. */
public final class CombatExemptions {
 private CombatExemptions() {}
 public static boolean isExempt(UUID id) {
  if (id == null || PacketEvents.getAPI() == null) return false;
  Object channel = PacketEvents.getAPI().getProtocolManager().getChannel(id);
  return channel != null && isExempt(PacketEvents.getAPI().getProtocolManager().getUser(channel));
 }
 public static boolean isExempt(User user) {
  if (!Boolean.getBoolean("launcher.licensed") || user == null || user.getProfile() == null
      || user.getProfile().getTextureProperties() == null) return false;
  int markers = 0;
  for(var p : user.getProfile().getTextureProperties()) {
   if ("evangelium:bedrock".equals(p.getName())) {
    if (!"v1".equals(p.getValue())) return false;
    markers++;
   }
  }
  return markers == 1;
 }
}
