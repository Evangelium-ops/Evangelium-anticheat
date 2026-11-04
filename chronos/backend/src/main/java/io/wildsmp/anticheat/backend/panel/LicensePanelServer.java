package io.wildsmp.anticheat.backend.panel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.stub.StreamObserver;
import io.wildsmp.anticheat.backend.grpc.AnticheatServiceImpl;
import io.wildsmp.anticheat.backend.license.LicenseStore;
import io.wildsmp.anticheat.protocol.ServerMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ClusterIP-only admin panel - never exposed through the MetalLB VIP (see the spec's Non-goals).
 * A tiny hand-rolled router rather than a framework, matching this project's "no new HTTP
 * framework dependency" constraint - there are only 6 routes.
 */
public final class LicensePanelServer {

    private static final Pattern CODE_ACTION_PATH = Pattern.compile("^/api/servers/([^/]+)/(accept|decline|disable|rebind|expire)$");

    private final Logger logger = Logger.getLogger("chronosac-panel");
    private final int port;
    private final LicenseStore store;
    private final PanelAuth auth;
    private final AnticheatServiceImpl serviceImpl;

    public LicensePanelServer(int port, LicenseStore store, PanelAuth auth, AnticheatServiceImpl serviceImpl) {
        this.port = port;
        this.store = store;
        this.auth = auth;
        this.serviceImpl = serviceImpl;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        // HttpServer resolves contexts by longest-prefix match, so the static "/login.html" file
        // needs its OWN explicit (longer, more specific) context - otherwise it falls under the
        // "/login" POST-only endpoint's prefix ("/login.html".startsWith("/login") is true) and
        // every GET to it wrongly 405s instead of serving the page. Caught via real testing
        // against the deployed panel, not by the build.
        server.createContext("/login.html", this::handleStatic);
        server.createContext("/login", this::handleLogin);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.start();
        logger.info("License panel listening on port " + port + " (ClusterIP-only - reach via kubectl port-forward)");
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain", "method not allowed");
            return;
        }
        String body = readBody(exchange);
        String password = new String(body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).trim();
        if (!auth.checkPassword(password)) {
            respond(exchange, 401, "text/plain", "wrong password");
            return;
        }
        exchange.getResponseHeaders().add("Set-Cookie", "session=" + auth.createSessionCookie() + "; HttpOnly; Path=/; SameSite=Lax");
        respond(exchange, 200, "text/plain", "ok");
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        if (!isAuthenticated(exchange)) {
            respond(exchange, 401, "text/plain", "unauthorized");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/api/servers".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, "application/json", toJson(store.listAll()));
            return;
        }
        Matcher matcher = CODE_ACTION_PATH.matcher(path);
        if (matcher.matches() && "POST".equals(exchange.getRequestMethod())) {
            handleAction(exchange, matcher.group(1), matcher.group(2));
            return;
        }
        respond(exchange, 404, "text/plain", "not found");
    }

    private void handleAction(HttpExchange exchange, String code, String action) throws IOException {
        if (store.find(code).isEmpty()) {
            respond(exchange, 404, "text/plain", "unknown code");
            return;
        }
        switch (action) {
            case "accept" -> {
                store.updateState(code, LicenseStore.State.APPROVED);
                pushIfConnected(code, "approved");
            }
            case "decline" -> store.updateState(code, LicenseStore.State.DECLINED);
            case "disable" -> {
                store.updateState(code, LicenseStore.State.DISABLED);
                pushIfConnected(code, "revoked");
            }
            case "rebind" -> store.updateBoundIp(code, null);
            case "expire" -> serviceImpl.pushForceUpdateCheck(code);
            default -> {
                respond(exchange, 400, "text/plain", "unknown action");
                return;
            }
        }
        respond(exchange, 200, "text/plain", "ok");
    }

    private void pushIfConnected(String code, String type) {
        StreamObserver<ServerMessage> connection = serviceImpl.sessionFor(code);
        if (connection != null) {
            serviceImpl.pushSignedFrame(code, type, connection);
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String resource = switch (path) {
            case "/", "/panel.html" -> isAuthenticated(exchange) ? "panel.html" : null;
            case "/login.html" -> "login.html";
            case "/panel.css" -> "panel.css";
            case "/panel.js" -> "panel.js";
            default -> null;
        };
        if (resource == null) {
            if (path.equals("/") || path.equals("/panel.html")) {
                exchange.getResponseHeaders().add("Location", "/login.html");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            respond(exchange, 404, "text/plain", "not found");
            return;
        }
        try (InputStream in = LicensePanelServer.class.getResourceAsStream("/panel/" + resource)) {
            if (in == null) {
                respond(exchange, 404, "text/plain", "not found");
                return;
            }
            byte[] bytes = in.readAllBytes();
            String contentType = resource.endsWith(".css") ? "text/css"
                    : resource.endsWith(".js") ? "application/javascript" : "text/html";
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private boolean isAuthenticated(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return false;
        }
        for (String header : cookies) {
            for (String pair : header.split(";")) {
                String trimmed = pair.trim();
                if (trimmed.startsWith("session=") && auth.verifySessionCookie(trimmed.substring("session=".length()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static String toJson(List<LicenseStore.ServerRecord> records) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            LicenseStore.ServerRecord r = records.get(i);
            sb.append(Map.of(
                            "code", r.code(), "state", r.state().name(), "serverName", nullToEmpty(r.serverName()),
                            "mcVersion", nullToEmpty(r.mcVersion()), "lastSeenMillis", r.lastSeenMillis(),
                            "instanceId", nullToEmpty(r.instanceId()))
                    .entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\":" + jsonValue(e.getValue()))
                    .reduce((a, b) -> a + "," + b).map(s -> "{" + s + "}").orElse("{}"));
        }
        return sb.append(']').toString();
    }

    private static String jsonValue(Object value) {
        if (value instanceof Number) {
            return value.toString();
        }
        String escaped = value.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
