package org.manator.velocityautostart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Plugin(id = "velocity-autostart", name = "VelocityAutostart", version = "1.2.0",
        description = "Starts and controls backend servers via the Crafty Controller API")
public class VelocityAutostartPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Config config;
    private CraftyApiClient craftyApi;
    private ControlApiServer controlApi;

    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();
    private final Set<String> stoppingServers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, String> readyToJoin = new ConcurrentHashMap<>();
    private final Map<String, Instant> emptySince = new ConcurrentHashMap<>();
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();
    private final Map<String, String> operationErrors = new ConcurrentHashMap<>();
    private final Map<String, Config.ManagedServer> managedServers = new ConcurrentHashMap<>();

    @Inject
    public VelocityAutostartPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = loadOrCreateConfig();
        loadManagedServersFromVelocityToml();
        this.craftyApi = new CraftyApiClient(config, logger);

        try {
            controlApi = new ControlApiServer(this, config.controlApiToken);
            controlApi.start(config.controlApiPort);
            logger.info("Hub control API listening on 127.0.0.1:" + config.controlApiPort);
        } catch (IOException exception) {
            logger.severe("Could not start Hub control API on port " + config.controlApiPort
                    + ": " + exception.getMessage());
        }

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("join").build(), new JoinCommand());
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("vas").build(), new VasCommand());
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("op").build(), new AdminCommand(true));
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("deop").build(), new AdminCommand(false));

        if (config.autoStopEnabled) {
            proxy.getScheduler().buildTask(this, this::checkIdleServers)
                    .delay(Duration.ofSeconds(config.autoStopCheckIntervalSeconds))
                    .repeat(Duration.ofSeconds(config.autoStopCheckIntervalSeconds))
                    .schedule();
        }

        logger.info("VelocityAutostart loaded, " + managedServers.size() + " managed servers configured.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (controlApi != null) {
            controlApi.close();
        }
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> initial = event.getInitialServer();
        if (initial.isEmpty()) {
            return;
        }
        String targetName = initial.get().getServerInfo().getName();
        Config.ManagedServer managed = managedServers.get(targetName);
        if (managed == null) {
            return;
        }

        RegisteredServer lobby = proxy.getServer(config.lobbyServer).orElse(null);
        if (stoppingServers.contains(targetName)) {
            if (lobby != null) {
                event.setInitialServer(lobby);
            }
            return;
        }
        if (isReachable(managed)) {
            return;
        }

        if (lobby == null) {
            logger.warning("Lobby server '" + config.lobbyServer + "' not found, cannot redirect player.");
            return;
        }
        event.setInitialServer(lobby);

        Player player = event.getPlayer();
        proxy.getScheduler().buildTask(this, () -> {
            player.sendMessage(message(config.messageStarting.replace("%server%", targetName)));
            triggerStartAndWatch(targetName, managed, player);
        }).delay(Duration.ofSeconds(1)).schedule();
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        String targetName = event.getOriginalServer().getServerInfo().getName();
        Config.ManagedServer managed = managedServers.get(targetName);
        if (managed == null) {
            return;
        }
        if (stoppingServers.contains(targetName)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(Component.text("That server is shutting down. Please wait."));
            return;
        }
        if (isReachable(managed)) {
            return;
        }

        Player player = event.getPlayer();
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(message(config.messageStarting.replace("%server%", targetName)));
        triggerStartAndWatch(targetName, managed, player);
    }

    private void triggerStartAndWatch(String serverName, Config.ManagedServer managed, Player player) {
        if (stoppingServers.contains(serverName) || !startingServers.add(serverName)) {
            return;
        }
        operationErrors.remove(serverName);
        startedAt.put(serverName, Instant.now());
        emptySince.remove(serverName);
        craftyApi.startServer(managed.craftyServerId);
        pollUntilReady(serverName, managed, player, 0);
    }

    private void pollUntilReady(String serverName, Config.ManagedServer managed, Player player, int elapsedSeconds) {
        proxy.getScheduler().buildTask(this, () -> {
            if (isReachable(managed)) {
                startingServers.remove(serverName);
                operationErrors.remove(serverName);
                if (player.isActive()) {
                    offerJoin(player, serverName);
                }
                return;
            }

            int nextElapsed = elapsedSeconds + config.pollIntervalSeconds;
            if (nextElapsed >= config.pollTimeoutSeconds) {
                startingServers.remove(serverName);
                operationErrors.put(serverName, "Server did not become reachable before the startup timeout.");
                if (player.isActive()) {
                    player.sendMessage(message(config.messageTimeout));
                }
                return;
            }
            pollUntilReady(serverName, managed, player, nextElapsed);
        }).delay(Duration.ofSeconds(config.pollIntervalSeconds)).schedule();
    }

    private void pollManagedStart(String serverName, Config.ManagedServer managed, int elapsedSeconds) {
        proxy.getScheduler().buildTask(this, () -> {
            if (isReachable(managed)) {
                startingServers.remove(serverName);
                operationErrors.remove(serverName);
                return;
            }
            int nextElapsed = elapsedSeconds + config.pollIntervalSeconds;
            if (nextElapsed >= config.pollTimeoutSeconds) {
                startingServers.remove(serverName);
                operationErrors.put(serverName, "Server did not become reachable before the startup timeout.");
                return;
            }
            pollManagedStart(serverName, managed, nextElapsed);
        }).delay(Duration.ofSeconds(config.pollIntervalSeconds)).schedule();
    }

    private void pollManagedStop(String serverName, Config.ManagedServer managed, int elapsedSeconds) {
        proxy.getScheduler().buildTask(this, () -> {
            if (!isReachable(managed)) {
                stoppingServers.remove(serverName);
                startedAt.remove(serverName);
                emptySince.remove(serverName);
                clearReadyToJoin(serverName);
                operationErrors.remove(serverName);
                return;
            }
            int nextElapsed = elapsedSeconds + 1;
            if (nextElapsed >= config.pollTimeoutSeconds) {
                stoppingServers.remove(serverName);
                operationErrors.put(serverName, "Server did not stop before the shutdown timeout.");
                return;
            }
            pollManagedStop(serverName, managed, nextElapsed);
        }).delay(Duration.ofSeconds(1)).schedule();
    }

    private void offerJoin(Player player, String serverName) {
        readyToJoin.put(player.getUniqueId(), serverName);
        Component clickable = Component.text(config.messageJoinButtonText)
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/join"))
                .hoverEvent(HoverEvent.showText(message(config.messageJoinHoverText.replace("%server%", serverName))));
        player.sendMessage(message(config.messageReady.replace("%server%", serverName))
                .append(Component.space()).append(clickable));
    }

    private void checkIdleServers() {
        for (Map.Entry<String, Config.ManagedServer> entry : managedServers.entrySet()) {
            String serverName = entry.getKey();
            Config.ManagedServer managed = entry.getValue();
            if (stoppingServers.contains(serverName) || startingServers.contains(serverName)) {
                continue;
            }
            RegisteredServer server = proxy.getServer(serverName).orElse(null);
            if (server == null || !isReachable(managed)) {
                emptySince.remove(serverName);
                startedAt.remove(serverName);
                continue;
            }

            if (!server.getPlayersConnected().isEmpty()) {
                emptySince.remove(serverName);
                continue;
            }

            Instant started = startedAt.get(serverName);
            if (started != null
                    && Duration.between(started, Instant.now()).getSeconds() < config.autoStopGracePeriodSeconds) {
                continue;
            }

            Instant since = emptySince.computeIfAbsent(serverName, key -> Instant.now());
            long idleSeconds = Duration.between(since, Instant.now()).getSeconds();
            if (idleSeconds >= config.autoStopIdleSeconds) {
                ControlResult result = requestStop(serverName);
                logger.info("Automatic idle-stop for " + serverName + ": " + result.message);
            }
        }
    }

    private void clearReadyToJoin(String serverName) {
        readyToJoin.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(serverName)) {
                return false;
            }
            Player player = proxy.getPlayer(entry.getKey()).orElse(null);
            if (player != null && player.isActive()) {
                player.sendMessage(message(config.messageServerStoppedIdle.replace("%server%", serverName)));
            }
            return true;
        });
    }

    private boolean isReachable(Config.ManagedServer managed) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(managed.host, managed.port), config.socketTimeoutMillis);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    ServerSnapshot getServerSnapshot(String serverName) {
        Config.ManagedServer managed = managedServers.get(serverName);
        if (managed == null) {
            return null;
        }
        RegisteredServer registered = proxy.getServer(serverName).orElse(null);
        int players = registered == null ? 0 : registered.getPlayersConnected().size();
        String status;
        String detail = "Server status retrieved.";
        if (stoppingServers.contains(serverName)) {
            status = "STOPPING";
        } else if (startingServers.contains(serverName)) {
            status = "STARTING";
        } else if (isReachable(managed)) {
            status = "RUNNING";
        } else if (operationErrors.containsKey(serverName)) {
            status = "ERROR";
            detail = operationErrors.get(serverName);
        } else {
            status = "STOPPED";
        }
        return new ServerSnapshot(status, players, detail);
    }

    ControlResult requestStart(String serverName) {
        Config.ManagedServer managed = managedServers.get(serverName);
        if (managed == null) {
            return new ControlResult(404, "ERROR", "Server is not managed: " + serverName, 0);
        }
        ServerSnapshot current = getServerSnapshot(serverName);
        if ("RUNNING".equals(current.status)) {
            return new ControlResult(200, "RUNNING", serverName + " is already running.", current.players);
        }
        if ("STARTING".equals(current.status)) {
            return new ControlResult(202, "STARTING", serverName + " is already starting.", current.players);
        }
        if ("STOPPING".equals(current.status)) {
            return new ControlResult(409, "STOPPING", serverName + " is currently stopping.", current.players);
        }

        operationErrors.remove(serverName);
        if (startingServers.add(serverName)) {
            startedAt.put(serverName, Instant.now());
            emptySince.remove(serverName);
            craftyApi.startServer(managed.craftyServerId);
            pollManagedStart(serverName, managed, 0);
        }
        return new ControlResult(202, "STARTING", "Startup requested for " + serverName + ".", current.players);
    }

    ControlResult requestStop(String serverName) {
        Config.ManagedServer managed = managedServers.get(serverName);
        if (managed == null) {
            return new ControlResult(404, "ERROR", "Server is not managed: " + serverName, 0);
        }
        ServerSnapshot current = getServerSnapshot(serverName);
        if ("STOPPED".equals(current.status)) {
            return new ControlResult(200, "STOPPED", serverName + " is already stopped.", 0);
        }
        if ("STARTING".equals(current.status)) {
            return new ControlResult(409, "STARTING", "Cannot stop " + serverName + " while it is starting.", 0);
        }
        if ("STOPPING".equals(current.status)) {
            return new ControlResult(202, "STOPPING", serverName + " is already stopping.", current.players);
        }

        RegisteredServer registered = proxy.getServer(serverName).orElse(null);
        int players = registered == null ? 0 : registered.getPlayersConnected().size();
        if (players > 0) {
            return new ControlResult(409, "RUNNING",
                    "Shutdown refused: " + players + " player(s) are still connected to " + serverName + ".", players);
        }

        if (!stoppingServers.add(serverName)) {
            return new ControlResult(202, "STOPPING", serverName + " is already stopping.", 0);
        }

        // Recheck after marking it as stopping. New pre-connect attempts are now denied.
        players = registered == null ? 0 : registered.getPlayersConnected().size();
        if (players > 0) {
            stoppingServers.remove(serverName);
            return new ControlResult(409, "RUNNING",
                    "Shutdown refused: a player connected while shutdown was being prepared.", players);
        }

        operationErrors.remove(serverName);
        craftyApi.stopServer(managed.craftyServerId);
        pollManagedStop(serverName, managed, 0);
        return new ControlResult(202, "STOPPING", "Shutdown requested for " + serverName + ".", 0);
    }

    private Component message(String legacyText) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }

    private Config loadOrCreateConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("config.json");
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Config loaded;
            if (!Files.exists(configPath)) {
                loaded = Config.example();
            } else {
                loaded = gson.fromJson(Files.readString(configPath), Config.class);
                if (loaded == null) {
                    loaded = Config.example();
                }
            }

            boolean changed = false;
            if (loaded.servers == null) {
                loaded.servers = new ConcurrentHashMap<>();
                changed = true;
            }
            if (loaded.adminUuids == null) {
                loaded.adminUuids = new java.util.ArrayList<>();
                changed = true;
            }
            if (loaded.controlApiPort == 25590) {
                loaded.controlApiPort = 28090;
                changed = true;
            } else if (loaded.controlApiPort < 1 || loaded.controlApiPort > 65535) {
                loaded.controlApiPort = 28090;
                changed = true;
            }
            if (loaded.controlApiToken == null || loaded.controlApiToken.isBlank()
                    || "CHANGE_ME".equals(loaded.controlApiToken)) {
                byte[] random = new byte[32];
                new SecureRandom().nextBytes(random);
                loaded.controlApiToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                changed = true;
            }
            if (!Files.exists(configPath) || changed) {
                Files.writeString(configPath, gson.toJson(loaded));
            }
            return loaded;
        } catch (IOException exception) {
            throw new RuntimeException("Could not load configuration", exception);
        }
    }

    /**
     * Reads Crafty IDs from the separate [autostart] table in Velocity's velocity.toml.
     * Server addresses are taken from Velocity's live registered-server configuration,
     * so names and backend addresses are never duplicated in this plugin's JSON file.
     */
    private void loadManagedServersFromVelocityToml() {
        Path velocityToml = dataDirectory.getParent().getParent().resolve("velocity.toml");
        Map<String, String> craftyIds = new java.util.LinkedHashMap<>();
        boolean autostartSectionFound = false;

        try {
            if (Files.exists(velocityToml)) {
                boolean inAutostart = false;
                Pattern entry = Pattern.compile("^(?:\"([^\"]+)\"|([A-Za-z0-9_-]+))\\s*=\\s*\"([^\"]+)\"\\s*(?:#.*)?$");
                for (String rawLine : Files.readAllLines(velocityToml)) {
                    String line = rawLine.trim();
                    if (line.startsWith("[") && line.endsWith("]")) {
                        inAutostart = "[autostart]".equals(line);
                        if (inAutostart) {
                            autostartSectionFound = true;
                        }
                        continue;
                    }
                    if (!inAutostart || line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    Matcher matcher = entry.matcher(line);
                    if (matcher.matches()) {
                        String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                        craftyIds.put(name, matcher.group(3));
                    } else {
                        logger.warning("Ignoring invalid [autostart] entry in velocity.toml: " + line);
                    }
                }
            } else {
                logger.warning("velocity.toml was not found at " + velocityToml);
            }
        } catch (IOException exception) {
            logger.warning("Could not read velocity.toml: " + exception.getMessage());
        }

        // Backward-compatible migration: append the old JSON Crafty IDs only when
        // velocity.toml does not already contain an [autostart] table.
        if (!autostartSectionFound && config.servers != null && !config.servers.isEmpty()
                && Files.exists(velocityToml)) {
            try {
                String existing = Files.readString(velocityToml);
                StringBuilder addition = new StringBuilder();
                if (!existing.endsWith("\n")) {
                    addition.append('\n');
                }
                addition.append("\n[autostart]\n");
                for (Map.Entry<String, Config.ManagedServer> entry : config.servers.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().craftyServerId != null
                            && !entry.getValue().craftyServerId.isBlank()) {
                        String name = entry.getKey().replace("\\", "\\\\").replace("\"", "\\\"");
                        addition.append('"').append(name).append("\" = \"")
                                .append(entry.getValue().craftyServerId).append("\"\n");
                        craftyIds.put(entry.getKey(), entry.getValue().craftyServerId);
                    }
                }
                if (!craftyIds.isEmpty()) {
                    Files.writeString(velocityToml, existing + addition);
                    autostartSectionFound = true;
                    logger.info("Migrated existing Crafty server IDs into velocity.toml [autostart].");
                }
            } catch (IOException exception) {
                logger.warning("Could not migrate legacy server IDs into velocity.toml: " + exception.getMessage());
            }
        }

        for (Map.Entry<String, String> entry : craftyIds.entrySet()) {
            String serverName = entry.getKey();
            String craftyId = entry.getValue();
            if (craftyId == null || craftyId.isBlank()) {
                logger.warning("Autostart server '" + serverName + "' has an empty Crafty ID; skipping it.");
                continue;
            }
            RegisteredServer registered = proxy.getServer(serverName).orElse(null);
            if (registered == null) {
                logger.warning("Autostart entry '" + serverName
                        + "' has a Crafty ID but is not registered in Velocity [servers]; skipping it.");
                continue;
            }
            InetSocketAddress address = registered.getServerInfo().getAddress();
            managedServers.put(serverName, new Config.ManagedServer(
                    craftyId, address.getHostString(), address.getPort()));
        }

        if (!craftyIds.isEmpty() && autostartSectionFound && config.servers != null && !config.servers.isEmpty()) {
            config.servers.clear();
            try {
                saveConfig();
                logger.info("Removed old duplicate server mappings from VelocityAutostart config.json.");
            } catch (IOException exception) {
                logger.warning("Could not clean old server mappings from config.json: " + exception.getMessage());
            }
        }
        logger.info("Loaded " + managedServers.size() + " managed server(s) from velocity.toml [autostart].");
    }

    List<String> getManagedServerNames() {
        return managedServers.keySet().stream().sorted().toList();
    }

    private boolean isAdmin(Player player) {
        return config.adminUuids.stream().anyMatch(uuid -> uuid.equalsIgnoreCase(player.getUniqueId().toString()));
    }

    private void saveConfig() throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(dataDirectory.resolve("config.json"), gson.toJson(config));
    }

    public Logger getLogger() {
        return logger;
    }

    static final class ServerSnapshot {
        final String status;
        final int players;
        final String message;

        ServerSnapshot(String status, int players, String message) {
            this.status = status;
            this.players = players;
            this.message = message;
        }
    }

    static final class ControlResult {
        final int httpCode;
        final String status;
        final String message;
        final int players;

        ControlResult(int httpCode, String status, String message, int players) {
            this.httpCode = httpCode;
            this.status = status;
            this.message = message;
            this.players = players;
        }
    }

    /** Console-only op/deop commands for managing proxy-side VelocityAutostart administrators. */
    private class AdminCommand implements SimpleCommand {
        private final boolean grant;

        private AdminCommand(boolean grant) {
            this.grant = grant;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (source instanceof Player) {
                source.sendMessage(Component.text("Use this command from the Velocity console in Crafty."));
                return;
            }
            String[] args = invocation.arguments();
            String label = grant ? "op" : "deop";
            if (args.length != 1) {
                source.sendMessage(Component.text("Usage: /" + label + " <online-player>"));
                return;
            }
            String playerName = args[0];
            Player target = proxy.getPlayer(playerName).orElse(null);
            if (target == null) {
                source.sendMessage(Component.text("Player must be online on the proxy so their account UUID can be verified."));
                return;
            }
            String uuid = target.getUniqueId().toString();
            boolean changed;
            if (grant) {
                changed = config.adminUuids.stream().noneMatch(existing -> existing.equalsIgnoreCase(uuid));
                if (changed) config.adminUuids.add(uuid);
            } else {
                changed = config.adminUuids.removeIf(existing -> existing.equalsIgnoreCase(uuid));
            }
            if (!changed) {
                source.sendMessage(Component.text(playerName + (grant ? " is already a proxy administrator." : " is not a proxy administrator.")));
                return;
            }
            try {
                saveConfig();
                source.sendMessage(Component.text(playerName + (grant
                        ? " is now a proxy administrator." : " is no longer a proxy administrator.")));
                logger.info(playerName + (grant ? " granted" : " revoked") + " VelocityAutostart administrator access.");
            } catch (IOException exception) {
                // Roll back the in-memory change if it could not be persisted.
                if (grant) config.adminUuids.removeIf(existing -> existing.equalsIgnoreCase(uuid));
                else config.adminUuids.add(uuid);
                source.sendMessage(Component.text("Could not save the administrator list: " + exception.getMessage()));
                logger.severe("Could not save proxy administrator list: " + exception.getMessage());
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return !(invocation.source() instanceof Player);
        }
    }

    private class JoinCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(message(config.messagePlayerOnly));
                return;
            }
            String serverName = readyToJoin.remove(player.getUniqueId());
            if (serverName == null) {
                player.sendMessage(message(config.messageNoServerReady));
                return;
            }
            RegisteredServer target = proxy.getServer(serverName).orElse(null);
            if (target == null) {
                player.sendMessage(message(config.messageServerNotFound));
                return;
            }
            player.createConnectionRequest(target).connectWithIndication();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source() instanceof Player;
        }
    }

    private class VasCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();
            if (args.length != 2) {
                source.sendMessage(Component.text("Usage: /vas <start|stop|status> <server>"));
                return;
            }
            String action = args[0].toLowerCase();
            String serverName = args[1].toLowerCase();

            // Keep command discovery available to everyone, but enforce admin access
            // when an actual start/stop operation is requested.
            if ((action.equals("start") || action.equals("stop"))
                    && source instanceof Player player
                    && !isAdmin(player)) {
                source.sendMessage(Component.text(
                        "You are not a proxy administrator. An administrator can grant access with /op <your-name> from the Velocity console."));
                return;
            }

            ControlResult result;
            switch (action) {
                case "start" -> result = requestStart(serverName);
                case "stop" -> result = requestStop(serverName);
                case "status" -> {
                    ServerSnapshot snapshot = getServerSnapshot(serverName);
                    if (snapshot == null) {
                        source.sendMessage(Component.text("Server is not managed: " + serverName));
                    } else {
                        source.sendMessage(Component.text(serverName + ": " + snapshot.status
                                + " (players: " + snapshot.players + ") - " + snapshot.message));
                    }
                    return;
                }
                default -> {
                    source.sendMessage(Component.text("Unknown action. Use start, stop, or status."));
                    return;
                }
            }
            source.sendMessage(Component.text(result.message));
        }


        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 0) {
                return List.of("start", "stop", "status");
            }
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                if (prefix.isEmpty()) {
                    return List.of("start", "stop", "status");
                }
                return List.of("start", "stop", "status").stream()
                        .filter(option -> option.startsWith(prefix))
                        .toList();
            }
            if (args.length == 2) {
                String prefix = args[1].toLowerCase();
                return managedServers.keySet().stream()
                        .filter(server -> server.toLowerCase().startsWith(prefix))
                        .sorted()
                        .toList();
            }
            return List.of();
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            // The command and its tab-completion should be visible to all players.
            // Permission is checked inside execute() for start/stop only.
            return true;
        }
    }
}
