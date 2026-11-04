package io.wildsmp.anticheat.bukkit.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Triggers a Pterodactyl server restart via its client API - ported from the same pattern already
 * used by WildSmpLimbo's {@code hibernate/PterodactylClient.java} (same headers/endpoint), trimmed
 * to just the one action this plugin needs.
 */
public final class PterodactylClient {

    private final String panelUrl;
    private final String apiKey;
    private final Logger logger;
    private final HttpClient http;

    public PterodactylClient(String panelUrl, String apiKey, Logger logger) {
        this.panelUrl = trimSlash(panelUrl == null ? "" : panelUrl.trim());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.logger = logger;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public boolean isConfigured() {
        return !panelUrl.isBlank() && !apiKey.isBlank();
    }

    public void sendRestartAsync(String serverUuid, Consumer<Boolean> onResult) {
        if (!isConfigured() || serverUuid == null || serverUuid.isBlank()) {
            onResult.accept(false);
            return;
        }
        URI uri = URI.create(panelUrl + "/api/client/servers/" + serverUuid.trim() + "/power");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "Application/vnd.pterodactyl.v1+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\":\"restart\"}"))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
                    if (!ok) {
                        logger.warning("Pterodactyl restart HTTP " + response.statusCode() + " for " + serverUuid);
                    }
                    return ok;
                })
                .exceptionally(t -> {
                    logger.log(Level.WARNING, "Pterodactyl restart failed for " + serverUuid, t);
                    return false;
                })
                .thenAccept(onResult);
    }

    private static String trimSlash(String url) {
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
