package io.wildsmp.anticheat.bukkit.license;
import io.knowledge.evangelium.ACLicenseProof;
import io.knowledge.evangelium.Reply;
import io.wildsmp.anticheat.protocol.*;
import java.nio.file.*;
import java.util.Properties;
/** Reads only the launcher's short-lived delegation; no second approval code or raw launcher license. */
public final class LauncherLicenseClient {
 private volatile String pendingInstance;
 public Hello hello(String server, String version) {
  try {
   Path path=Path.of(System.getProperty("launcher.anticheat.credentials"));
   Properties credentials=new Properties();try(var input=Files.newInputStream(path)){credentials.load(input);}
   String proof=ACLicenseProof.hello(credentials,System.currentTimeMillis());
   pendingInstance=Reply.decode(proof).get("instance");
   return Hello.newBuilder().setLauncherProof(proof).setInstanceId(pendingInstance).setServerName(server).setMcVersion(version).setModVersion("AC").build();
  }catch(Exception e){throw new IllegalStateException("No current launcher authorization for AC combat",e);}
 }
 public boolean accepts(LauncherApproval approval) {
  long now=System.currentTimeMillis();
  return approval.getInstance().equals(pendingInstance) && approval.getExpiresMillis()>now && approval.getExpiresMillis()<=now+150_000;
 }
}
