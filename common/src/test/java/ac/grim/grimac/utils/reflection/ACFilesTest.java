package ac.grim.grimac.utils.reflection;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ACFilesTest {
 @Test void preservesBothEnginesAndExistingACSettings(@TempDir Path plugins) throws Exception {
  Files.createDirectories(plugins.resolve("GrimAC"));Files.createDirectories(plugins.resolve("ChronosAC"));
  Files.writeString(plugins.resolve("GrimAC/config.yml"),"movement: saved");
  Files.writeString(plugins.resolve("ChronosAC/config.yml"),"backend: configured");
  Files.writeString(plugins.resolve("ChronosAC/license.yml"),"test-instance: preserve");
  ACFiles.migrate(plugins);
  assertEquals("movement: saved",Files.readString(plugins.resolve("AC/config.yml")));
  assertEquals("backend: configured",Files.readString(plugins.resolve("AC/chronos.yml")));
  assertEquals("test-instance: preserve",Files.readString(plugins.resolve("AC/license.yml")));
  Files.writeString(plugins.resolve("AC/chronos.yml"),"new settings");ACFiles.migrate(plugins);
  assertEquals("new settings",Files.readString(plugins.resolve("AC/chronos.yml")));
  assertEquals("backend: configured",Files.readString(plugins.resolve("ChronosAC/config.yml")));
 }
}
