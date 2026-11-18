package org.manator.velocityautostart;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Small authenticated HTTP bridge for the Paper HubControl plugin.
 * It binds only to IPv4 loopback; it is not exposed to the LAN.
 */
public final class ControlApiServer implements AutoCloseable {

    private final VelocityAutostartPlugin plugin;
    private final String token;
    private final Gson gson = new Gson();
    private HttpServer server;

    public ControlApiServer(VelocityAutostartPlugin plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/api/v1/servers/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "VelocityAutostart-ControlAPI");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                send(exchange, 401, "ERROR", "Unauthorized", 0);
                return;
            }

            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length != 6 || !"api".equals(parts[1]) || !"v1".equals(parts[2])
                    || !"servers".equals(parts[3])) {
                send(exchange, 404, "ERROR", "Unknown endpoint", 0);
                return;
            }

            String serverName = parts[4];
            String action = parts[5];
            String method = exchange.getRequestMethod();

            if ("status".equals(action) && "GET".equals(method)) {
                VelocityAutostartPlugin.ServerSnapshot snapshot = plugin.getServerSnapshot(serverName);
                if (snapshot == null) {
                    send(exchange, 404, "ERROR", "Server is not managed", 0);
                } else {
                    send(exchange, 200, snapshot.status, snapshot.message, snapshot.players);
                }
                return;
            }

            if ("start".equals(action) && "POST".equals(method)) {
                VelocityAutostartPlugin.ControlResult result = plugin.requestStart(serverName);
                send(exchange, result.httpCode, result.status, result.message, result.players);
                return;
            }

            if ("stop".equals(action) && "POST".equals(method)) {
                VelocityAutostartPlugin.ControlResult result = plugin.requestStop(serverName);
                send(exchange, result.httpCode, result.status, result.message, result.players);
                return;
            }

            send(exchange, 405, "ERROR", "Method or action not supported", 0);
        } catch (Exception exception) {
            plugin.getLogger().warning("Control API request failed: " + exception.getMessage());
            send(exchange, 500, "ERROR", "Internal control API error", 0);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("Authorization");
        if (supplied == null || !supplied.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private void send(HttpExchange exchange, int code, String status, String message, int players)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        body.addProperty("message", message);
        body.addProperty("players", players);
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
    }
}
