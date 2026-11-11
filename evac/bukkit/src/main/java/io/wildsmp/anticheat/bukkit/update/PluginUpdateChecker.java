package io.wildsmp.anticheat.bukkit.update;

import io.wildsmp.anticheat.bukkit.AnticheatBukkitPlugin;
import io.wildsmp.anticheat.bukkit.backend.BackendClient;
import io.wildsmp.anticheat.bukkit.scheduling.FoliaSchedulerAdapter;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically asks backend (over the existing authenticated gRPC channel) whether the jar this
 * server is running is stale, compared by SHA-256 (see the design spec's Context on why not a
 * version number). On a mismatch: downloads the new jar to a temp file in the same directory,
 * re-verifies its hash, atomically replaces the live jar (safe on this Linux-only deployment
 * target - no helper process needed, see Global Constraints), then triggers a restart via {@link
 * PterodactylClient} if one is configured. Every failure at every step is logged and simply
 * retried next interval - never throws out of the scheduled task.
 */
public final class PluginUpdateChecker {

    private final AnticheatBukkitPlugin plugin;
    private final Logger logger;
    private final BackendClient backendClient;
    private final Path selfJarPath;
    private final boolean enabled;
    private final PterodactylClient pterodactylClient;
    private final String serverUuid;
    private final String selfSha256;
    // The sha256 of whatever this checker has already downloaded, verified, and swapped onto disk
    // this JVM's lifetime, if any - without this, a server with Pterodactyl auto-restart NOT
    // configured (selfSha256 can then never refresh without a MANUAL restart) would re-detect the
    // same already-staged version as "new" on every single check forever, re-downloading and
    // re-broadcasting on a loop even though nothing has actually changed since the first time.
    // Confirmed live: exactly this loop, once every check-interval-minutes, on a test server with
    // pterodactyl.api-key/server-uuid left blank.
    private volatile String stagedSha256 = null;

    public PluginUpdateChecker(AnticheatBukkitPlugin plugin, BackendClient backendClient, Path selfJarPath,
                                boolean enabled, PterodactylClient pterodactylClient, String serverUuid) throws IOException {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.backendClient = backendClient;
        this.selfJarPath = selfJarPath;
        this.enabled = enabled;
        this.pterodactylClient = pterodactylClient;
        this.serverUuid = serverUuid;
        // Hashed once here, not on every check - selfJarPath only ever changes via this class's
        // own swap in finishDownload, which restarts the server before another check could run
        // (when Pterodactyl auto-restart IS configured; see stagedSha256 above for when it isn't).
        this.selfSha256 = JarHasher.sha256Hex(selfJarPath);
    }

    /** Compares two SHA-256 hex strings - an empty remote hash means backend has nothing to serve. */
    public static boolean isUpdateAvailable(String currentSha256, String remoteSha256) {
        return remoteSha256 != null && !remoteSha256.isEmpty() && !remoteSha256.equals(currentSha256);
    }

    public void start(long intervalMinutes) {
        if (!enabled) {
            logger.info("Plugin auto-update is disabled (auto-update.enabled: false).");
            return;
        }
        long periodTicks = intervalMinutes * 60L * 20L; // 20 ticks/second
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkNow, periodTicks, periodTicks);
    }

    /** Runs a check immediately, off the usual periodic timer - see AnticheatBukkitPlugin's ForceUpdateCheck wiring. */
    public void checkNow() {
        backendClient.checkForUpdate(
                response -> {
                    String remoteSha256 = response.getSha256();
                    if (remoteSha256.equals(stagedSha256)) {
                        return; // already downloaded and staged this exact version - waiting on a
                                // restart (automatic or manual) to actually pick it up, nothing new
                                // to do until then
                    }
                    if (isUpdateAvailable(selfSha256, remoteSha256)) {
                        downloadAndApply(remoteSha256);
                    }
                },
                error -> logger.log(Level.FINE, "Update check failed - will retry next interval.", error));
    }

    private void downloadAndApply(String expectedSha256) {
        Path tempFile = selfJarPath.resolveSibling(selfJarPath.getFileName() + ".new");
        OutputStream[] outHolder = new OutputStream[1];
        try {
            outHolder[0] = Files.newOutputStream(tempFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not open temp file for update download.", e);
            return;
        }
        backendClient.downloadPluginJar(
                chunk -> {
                    try {
                        outHolder[0].write(chunk);
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Failed writing update chunk to disk.", e);
                    }
                },
                () -> finishDownload(tempFile, outHolder[0], expectedSha256),
                error -> {
                    closeQuietly(outHolder[0]);
                    deleteQuietly(tempFile);
                    logger.log(Level.WARNING, "Update download failed - will retry next interval.", error);
                });
    }

    private void finishDownload(Path tempFile, OutputStream out, String expectedSha256) {
        closeQuietly(out);
        String actualSha256;
        try {
            actualSha256 = JarHasher.sha256Hex(tempFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not hash downloaded update.", e);
            deleteQuietly(tempFile);
            return;
        }
        if (!actualSha256.equals(expectedSha256)) {
            logger.warning("Downloaded update's hash didn't match what backend advertised - discarding, will retry next interval.");
            deleteQuietly(tempFile);
            return;
        }
        try {
            Files.move(tempFile, selfJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not swap in the verified update.", e);
            deleteQuietly(tempFile);
            return;
        }
        stagedSha256 = actualSha256;
        logger.info("EVAC plugin updated (sha256=" + actualSha256 + ") - staged on disk.");
        applyUpdate();
    }

    private void applyUpdate() {
        FoliaSchedulerAdapter scheduler = new FoliaSchedulerAdapter();
        scheduler.runOnce(plugin, () -> {
            // Only claim a restart is happening when one actually will - broadcasting this
            // unconditionally (the original bug) misled players into thinking a restart was
            // imminent even when Pterodactyl auto-restart isn't configured and nothing was about
            // to happen.
            if (pterodactylClient.isConfigured()) {
                Bukkit.broadcastMessage("§c[EVAC] §fA new version was installed | restarting the server now.");
                pterodactylClient.sendRestartAsync(serverUuid, ok -> {
                    if (!ok) {
                        logger.warning("Pterodactyl restart call failed - the new jar is staged and will apply on the next restart, whenever that is.");
                    }
                });
            } else {
                logger.info("Pterodactyl isn't configured - the new jar is staged and will apply on the next manual restart.");
            }
        }, 1L);
    }

    private static void closeQuietly(OutputStream out) {
        try {
            out.close();
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
