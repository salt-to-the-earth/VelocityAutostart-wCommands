package org.manator.hubcontrol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HubControlPlugin extends JavaPlugin implements CommandExecutor {

    private final Gson gson = new Gson();
    private final Map<String, ServerStatus> statusCache = new ConcurrentHashMap<>();
    private HttpClient http;
    private String apiUrl;
    private String apiToken;
    private int requestTimeoutMillis;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        apiUrl = getConfig().getString("velocity-api-url", "http://127.0.0.1:25590").replaceAll("/+$", "");
        apiToken = getConfig().getString("api-token", "CHANGE_ME");
        requestTimeoutMillis = Math.max(250, getConfig().getInt("request-timeout-millis", 1500));
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(requestTimeoutMillis))
                .build();

        if (getCommand("vas") != null) {
            getCommand("vas").setExecutor(this);
        }

        int refreshSeconds = Math.max(1, getConfig().getInt("status-refresh-seconds", 2));
        refreshTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, this::refreshKnownStatuses, 1L, refreshSeconds * 20L);

        getLogger().info("HubControl enabled. Velocity API: " + apiUrl);
        if ("CHANGE_ME".equals(apiToken) || apiToken.isBlank()) {
            getLogger().warning("Set api-token in plugins/HubControl/config.yml to the token in VelocityAutostart config.json.");
        }
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        if (http != null) {
            http.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /vas <start|stop|status|isrunning|isstopped> <server>");
            return false;
        }

        String action = args[0].toLowerCase();
        String server = args[1].toLowerCase();

        switch (action) {
            case "start" -> {
                sendOperation(sender, server, "start");
                return true;
            }
            case "stop" -> {
                sendOperation(sender, server, "stop");
                return true;
            }
            case "status" -> {
                refreshStatus(server, sender);
                return true;
            }
            case "isrunning" -> {
                ServerStatus status = statusCache.get(server);
                return status != null && "RUNNING".equals(status.status);
            }
            case "isstopped" -> {
                ServerStatus status = statusCache.get(server);
                return status != null && "STOPPED".equals(status.status);
            }
            default -> {
                sender.sendMessage("Unknown action. Use start, stop, status, isrunning, or isstopped.");
                return false;
            }
        }
    }

    private void sendOperation(CommandSender sender, String server, String operation) {
        String path = "/api/v1/servers/" + encodePathSegment(server) + "/" + operation;
        HttpRequest request = request(path).POST(HttpRequest.BodyPublishers.noBody()).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    JsonObject body = parseObject(response.body());
                    String message = body.has("message") ? body.get("message").getAsString()
                            : operation.toUpperCase() + " request returned HTTP " + response.statusCode();
                    if (body.has("status")) {
                        statusCache.put(server, new ServerStatus(body.get("status").getAsString(),
                                body.has("players") ? body.get("players").getAsInt() : 0));
                    }
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage("[HubControl] " + message));
                })
                .exceptionally(error -> {
                    getLogger().warning("Could not contact VelocityAutostart: " + error.getMessage());
                    getServer().getScheduler().runTask(this,
                            () -> sender.sendMessage("[HubControl] Could not contact VelocityAutostart."));
                    return null;
                });
    }

    private void refreshKnownStatuses() {
        for (String server : statusCache.keySet()) {
            refreshStatus(server, null);
        }
    }

    private void refreshStatus(String server, CommandSender sender) {
        HttpRequest request = request("/api/v1/servers/" + encodePathSegment(server) + "/status").GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    JsonObject body = parseObject(response.body());
                    if (response.statusCode() >= 200 && response.statusCode() < 300 && body.has("status")) {
                        ServerStatus status = new ServerStatus(body.get("status").getAsString(),
                                body.has("players") ? body.get("players").getAsInt() : 0);
                        statusCache.put(server, status);
                        if (sender != null) {
                            getServer().getScheduler().runTask(this, () -> sender.sendMessage(
                                    "[HubControl] " + server + ": " + status.status
                                            + " (players: " + status.players + ")"));
                        }
                    } else if (sender != null) {
                        String message = body.has("message") ? body.get("message").getAsString()
                                : "Status request failed (HTTP " + response.statusCode() + ").";
                        getServer().getScheduler().runTask(this,
                                () -> sender.sendMessage("[HubControl] " + message));
                    }
                })
                .exceptionally(error -> {
                    if (sender != null) {
                        getServer().getScheduler().runTask(this, () ->
                                sender.sendMessage("[HubControl] Could not contact VelocityAutostart."));
                    }
                    return null;
                });
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(Duration.ofMillis(requestTimeoutMillis))
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/json");
    }

    private JsonObject parseObject(String json) {
        try {
            return gson.fromJson(json, JsonObject.class);
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private String encodePathSegment(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace(" ", "%20");
    }

    private static final class ServerStatus {
        private final String status;
        private final int players;

        private ServerStatus(String status, int players) {
            this.status = status;
            this.players = players;
        }
    }
}
