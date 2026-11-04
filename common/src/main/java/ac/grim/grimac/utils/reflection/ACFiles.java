package ac.grim.grimac.utils.reflection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
/** Migrate settings/identity without changing or overwriting either installation's saved files. */
public final class ACFiles {
 private ACFiles() {}
 public static void migrate(Path plugins) throws IOException {
  Path target=plugins.resolve("AC");
  for (String engine : new String[]{"GrimAC","ChronosAC"}) {
   Path old=plugins.resolve(engine);
   if (!Files.isDirectory(old) || Files.isSymbolicLink(old)) continue;
   try(var paths=Files.walk(old)) {
    for(Path source:paths.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
     Path relative=old.relativize(source);
     Path destination=engine.equals("ChronosAC") && relative.toString().equals("config.yml")
             ? target.resolve("chronos.yml") : target.resolve(relative);
     if(Files.exists(destination)) continue;
     Files.createDirectories(destination.getParent());
     Files.copy(source,destination);
    }
   }
  }
 }
}
