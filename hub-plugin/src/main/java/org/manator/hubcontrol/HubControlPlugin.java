package org.manator.hubcontrol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HubControlPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    // Command-block automation reads these values:
    // 0=STOPPED, 1=RUNNING, 2=STARTING, 3=STOPPING, 4=ERROR.
    public static final String SCOREBOARD_OBJECTIVE = "vas_state";

    private final Gson gson = new Gson();
    private final Map<String, ServerStatus> statusCache = new ConcurrentHashMap<>();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final Set<String> knownServers = ConcurrentHashMap.newKeySet();
    private HttpClient http;
    private String apiUrl;
    private String apiToken;
    private int requestTimeoutMillis;
    private BukkitTask refreshTask;
    private Objective stateObjective;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String configuredApiUrl = getConfig().getString("velocity-api-url", "http://127.0.0.1:28090");
        boolean configUpdated = false;
        if ("http://127.0.0.1:25590".equals(configuredApiUrl)) {
            configuredApiUrl = "http://127.0.0.1:28090";
            getConfig().set("velocity-api-url", configuredApiUrl);
            configUpdated = true;
            getLogger().info("Updated the local Velocity API port from 25590 to 28090.");
        }
        if (getConfig().contains("managed-servers")) {
            getConfig().set("managed-servers", null);
            configUpdated = true;
            getLogger().info("Removed the obsolete Hub server list; servers are now discovered from Velocity.");
        }
        if (configUpdated) {
            saveConfig();
        }
        apiUrl = configuredApiUrl.replaceAll("/+$", "");
        apiToken = getConfig().getString("api-token", "CHANGE_ME");
        requestTimeoutMillis = Math.max(250, getConfig().getInt("request-timeout-millis", 1500));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(requestTimeoutMillis)).build();
        initializeScoreboard();

        if (getCommand("hvas") != null) {
            getCommand("hvas").setExecutor(this);
            getCommand("hvas").setTabCompleter(this);
        }

        int refreshSeconds = Math.max(1, getConfig().getInt("status-refresh-seconds", 2));
        refreshTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, this::refreshKnownStatuses, 1L, refreshSeconds * 20L);

        getLogger().info("HubControl enabled. Velocity API: " + apiUrl);
        getLogger().info("Command-block state objective: " + SCOREBOARD_OBJECTIVE
                + " (0=STOPPED, 1=RUNNING, 2=STARTING, 3=STOPPING, 4=ERROR)");
        if ("CHANGE_ME".equals(apiToken) || apiToken.isBlank()) {
            getLogger().warning("Set api-token in plugins/HubControl/config.yml to the controlApiToken in VelocityAutostart config.json.");
        }
    }

    private void initializeScoreboard() {
        Scoreboard scoreboard = getServer().getScoreboardManager().getMainScoreboard();
        stateObjective = scoreboard.getObjective(SCOREBOARD_OBJECTIVE);
        if (stateObjective == null) {
            stateObjective = scoreboard.registerNewObjective(
                    SCOREBOARD_OBJECTIVE, Criteria.DUMMY,
                    Component.text("Velocity Server State"), RenderType.INTEGER);
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
            sender.sendMessage("Usage: /hvas <start|stop|status> <server>");
            return false;
        }

        String action = args[0].toLowerCase();
        String server = args[1].toLowerCase();

        if (sender instanceof BlockCommandSender blockSender) {
            org.bukkit.Location location = blockSender.getBlock().getLocation();
            getLogger().info("Command block at " + location.getWorld().getName() + " "
                    + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                    + " issued /hvas " + action + " " + server + ".");
        }

        switch (action) {
            case "start", "stop" -> {
                boolean authorized = sender instanceof BlockCommandSender
                        || sender instanceof ConsoleCommandSender
                        || (sender instanceof org.bukkit.entity.Player player && player.isOp());
                if (!authorized) {
                    sender.sendMessage("[HubControl] You must be OP on the Hub to control servers.");
                    return false;
                }
                sendOperation(sender, server, action);
                return true;
            }
            case "status" -> {
                refreshStatus(server, sender);
                return true;
            }
            default -> {
                sender.sendMessage("Unknown action. Use start, stop, or status.");
                return false;
            }
        }
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "status").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return knownServers.stream()
                    .filter(server -> server.startsWith(prefix))
                    .sorted()
                    .toList();
        }
        return List.of();
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
                        cacheStatus(server, new ServerStatus(body.get("status").getAsString(),
                                body.has("players") ? body.get("players").getAsInt() : 0));
                    }
                    getServer().getScheduler().runTask(this,
                            () -> sender.sendMessage("[HubControl] " + message));
                })
                .exceptionally(error -> {
                    getLogger().warning("Could not contact VelocityAutostart: " + error.getMessage());
                    getServer().getScheduler().runTask(this,
                            () -> sender.sendMessage("[HubControl] Could not contact VelocityAutostart."));
                    return null;
                });
    }

    private void refreshKnownStatuses() {
        // Discover the managed server list from VelocityAutostart. HubControl keeps no
        // separate server-name list, so additions in velocity.toml appear automatically.
        if (!refreshing.add("__server_discovery__")) {
            return;
        }
        HttpRequest request = request("/api/v1/servers").GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    JsonObject body = parseObject(response.body());
                    if (response.statusCode() < 200 || response.statusCode() >= 300
                            || !body.has("servers") || !body.get("servers").isJsonArray()) {
                        getLogger().warning("Could not discover managed servers from VelocityAutostart (HTTP "
                                + response.statusCode() + ").");
                        return;
                    }

                    Set<String> discovered = ConcurrentHashMap.newKeySet();
                    for (com.google.gson.JsonElement value : body.getAsJsonArray("servers")) {
                        if (value.isJsonPrimitive()) {
                            String name = value.getAsString().toLowerCase();
                            if (!name.isBlank()) {
                                discovered.add(name);
                            }
                        }
                    }
                    knownServers.clear();
                    knownServers.addAll(discovered);
                    for (String cached : statusCache.keySet()) {
                        if (!discovered.contains(cached)) {
                            statusCache.remove(cached);
                        }
                    }
                    for (String server : discovered) {
                        refreshStatus(server, null);
                    }
                })
                .exceptionally(error -> {
                    getLogger().warning("Could not contact VelocityAutostart for server discovery: "
                            + error.getMessage());
                    return null;
                })
                .whenComplete((ignored, error) -> refreshing.remove("__server_discovery__"));
    }

    private void refreshStatus(String server, CommandSender sender) {
        if (!refreshing.add(server)) {
            return;
        }
        HttpRequest request = request("/api/v1/servers/" + encodePathSegment(server) + "/status").GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    JsonObject body = parseObject(response.body());
                    if (response.statusCode() >= 200 && response.statusCode() < 300 && body.has("status")) {
                        ServerStatus status = new ServerStatus(body.get("status").getAsString(),
                                body.has("players") ? body.get("players").getAsInt() : 0);
                        cacheStatus(server, status);
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
                    cacheStatus(server, new ServerStatus("ERROR", 0));
                    if (sender != null) {
                        getServer().getScheduler().runTask(this, () ->
                                sender.sendMessage("[HubControl] Could not contact VelocityAutostart."));
                    }
                    return null;
                })
                .whenComplete((ignored, error) -> refreshing.remove(server));
    }

    private void cacheStatus(String server, ServerStatus status) {
        statusCache.put(server, status);
        getServer().getScheduler().runTask(this, () -> {
            if (stateObjective != null) {
                stateObjective.getScore("$" + server).setScore(status.score);
            }
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
            JsonObject parsed = gson.fromJson(json, JsonObject.class);
            return parsed == null ? new JsonObject() : parsed;
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
        private final int score;

        private ServerStatus(String status, int players) {
            this.status = status;
            this.players = players;
            this.score = switch (status) {
                case "RUNNING" -> 1;
                case "STARTING" -> 2;
                case "STOPPING" -> 3;
                case "ERROR" -> 4;
                default -> 0;
            };
        }
    }
}
