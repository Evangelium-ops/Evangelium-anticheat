package io.wildsmp.anticheat.bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
/** Move the old official LAN endpoint onto the owner's public TLS passthrough route. */
final class CombatEndpoint {
 private CombatEndpoint() {}
 static boolean migrate(YamlConfiguration config) {
  if(!"192.168.50.75".equals(config.getString("backend.host")) || config.getInt("backend.port",9443)!=9443)return false;
  config.set("backend.host","198.251.88.40");config.set("backend.tls-server-name","192.168.50.75");return true;
 }
}
