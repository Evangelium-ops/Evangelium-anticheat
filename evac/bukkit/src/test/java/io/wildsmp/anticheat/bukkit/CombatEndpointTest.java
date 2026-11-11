package io.wildsmp.anticheat.bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CombatEndpointTest {
 @Test void officialLegacyAddressMovesToVpsAndKeepsTlsIdentity() {
  var yaml=new YamlConfiguration();yaml.set("backend.host","192.168.50.75");yaml.set("backend.port",9443);yaml.set("backend.tls",true);
  assertTrue(CombatEndpoint.migrate(yaml));assertEquals("198.251.88.40",yaml.getString("backend.host"));assertEquals("192.168.50.75",yaml.getString("backend.tls-server-name"));assertTrue(yaml.getBoolean("backend.tls"));assertFalse(CombatEndpoint.migrate(yaml));
 }
 @Test void customBackendOrPortIsPreserved() {
  var yaml=new YamlConfiguration();yaml.set("backend.host","custom.example");assertFalse(CombatEndpoint.migrate(yaml));
  yaml.set("backend.host","192.168.50.75");yaml.set("backend.port",1234);assertFalse(CombatEndpoint.migrate(yaml));
 }
}
