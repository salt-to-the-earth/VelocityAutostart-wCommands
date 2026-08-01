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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Plugin(id = "velocity-autostart", name = "VelocityAutostart", version = "1.0.0",
        description = "Starts backend servers on demand via the Crafty Controller API")
public class VelocityAutostartPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Config config;
    private CraftyApiClient craftyApi;

    // prevents duplicate start calls / poll tasks for the same server
    private final Set<String> startingServers = ConcurrentHashMap.newKeySet();

    // players for whom a target server is ready and who can join via /join or click.
    // UUID -> Server name (Velocity internal name from config.servers)
    private final ConcurrentHashMap<UUID, String> readyToJoin = new ConcurrentHashMap<>();

    // Auto-Stop Tracking: since when has the server been empty? / since when has it been running?
    private final Map<String, Instant> emptySince = new ConcurrentHashMap<>();
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();

    @Inject
    public VelocityAutostartPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = loadOrCreateConfig();
        this.craftyApi = new CraftyApiClient(config, logger);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("join").build(),
                new JoinCommand());

        if (config.autoStopEnabled) {
            proxy.getScheduler().buildTask(this, this::checkIdleServers)
                    .delay(Duration.ofSeconds(config.autoStopCheckIntervalSeconds))
                    .repeat(Duration.ofSeconds(config.autoStopCheckIntervalSeconds))
                    .schedule();
        }

        logger.info("VelocityAutostart loaded, " + config.servers.size() + " managed servers configured.");
    }

    /** Catches the initial login (player first connects to the proxy). */
    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> initial = event.getInitialServer();
        if (initial.isEmpty()) {
            return;
        }
        String targetName = initial.get().getServerInfo().getName();
        Config.ManagedServer managed = config.servers.get(targetName);
        if (managed == null) {
            return; // not managed by us, continue normally
        }

        if (isReachable(managed)) {
            return; // Server is already running, nothing to do
        }

        // Instead, send the player to the lobby, then start and poll
        RegisteredServer lobby = proxy.getServer(config.lobbyServer).orElse(null);
        if (lobby == null) {
            logger.warning("Lobby server '" + config.lobbyServer + "' not found, cannot redirect player.");
            return;
        }
        event.setInitialServer(lobby);

        Player player = event.getPlayer();
        proxy.getScheduler().buildTask(this, () -> {
            player.sendMessage(message(config.messageStarting.replace("%server%", targetName)));
            triggerStartAndWatch(targetName, managed, player);
        }).delay(Duration.ofSeconds(1)).schedule(); // short delay until player is in the lobby
    }

    /** Catches server switch, e.g., via /server or portal plugins. */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        String targetName = event.getOriginalServer().getServerInfo().getName();
        Config.ManagedServer managed = config.servers.get(targetName);
        if (managed == null) {
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
        if (!startingServers.add(serverName)) {
            return; // already starting/polling
        }
        startedAt.put(serverName, Instant.now());
        emptySince.remove(serverName);
        craftyApi.startServer(managed.craftyServerId);
        pollUntilReady(serverName, managed, player, 0);
    }

    private void pollUntilReady(String serverName, Config.ManagedServer managed, Player player, int elapsedSeconds) {
        proxy.getScheduler().buildTask(this, () -> {
            if (isReachable(managed)) {
                startingServers.remove(serverName);
                if (player.isActive()) {
                    offerJoin(player, serverName);
                }
                return;
            }

            int nextElapsed = elapsedSeconds + config.pollIntervalSeconds;
            if (nextElapsed >= config.pollTimeoutSeconds) {
                startingServers.remove(serverName);
                if (player.isActive()) {
                    player.sendMessage(message(config.messageTimeout));
                }
                return;
            }

            pollUntilReady(serverName, managed, player, nextElapsed);
        }).delay(Duration.ofSeconds(config.pollIntervalSeconds)).schedule();
    }

    /**
     * Server is ready: no automatic connection anymore, instead offer a clickable chat message
     * (or /join). The player decides when to switch.
     */
    private void offerJoin(Player player, String serverName) {
        readyToJoin.put(player.getUniqueId(), serverName);

        Component clickable = Component.text(config.messageJoinButtonText)
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/join"))
                .hoverEvent(HoverEvent.showText(message(config.messageJoinHoverText.replace("%server%", serverName))));

        player.sendMessage(message(config.messageReady.replace("%server%", serverName))
                .append(Component.space())
                .append(clickable));
    }

    /**
     * Periodically called (see onProxyInitialize). Checks for each managed, currently running
     * server if it is idle, and stops it via the Crafty API after autoStopIdleSeconds have passed.
     */
    private void checkIdleServers() {
        for (Map.Entry<String, Config.ManagedServer> entry : config.servers.entrySet()) {
            String serverName = entry.getKey();
            Config.ManagedServer managed = entry.getValue();

            RegisteredServer server = proxy.getServer(serverName).orElse(null);
            if (server == null) {
                continue;
            }

            if (!isReachable(managed)) {
                // Server is not reachable anyway, reset state
                emptySince.remove(serverName);
                startedAt.remove(serverName);
                continue;
            }

            int playerCount = server.getPlayersConnected().size();
            if (playerCount > 0) {
                emptySince.remove(serverName);
                continue;
            }

            // Server is running and idle
            Instant started = startedAt.get(serverName);
            if (started != null
                    && Duration.between(started, Instant.now()).getSeconds() < config.autoStopGracePeriodSeconds) {
                continue; // Grace period after start, do not touch yet
            }

            Instant since = emptySince.computeIfAbsent(serverName, k -> Instant.now());
            long idleSeconds = Duration.between(since, Instant.now()).getSeconds();
            if (idleSeconds >= config.autoStopIdleSeconds) {
                logger.info(serverName + " has been idle for " + idleSeconds + "s, stopping via Crafty API.");
                craftyApi.stopServer(managed.craftyServerId);
                emptySince.remove(serverName);
                startedAt.remove(serverName);
                clearReadyToJoin(serverName);
            }
        }
    }

    /**
     * Removes all readyToJoin entries that point to the specified server, e.g., because
     * it was stopped due to inactivity. Affected players are informed so that /join does not
     * run into an empty void.
     */
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

    /** Simple TCP connect check instead of another Crafty API call -> no auth overhead during polling. */
    private boolean isReachable(Config.ManagedServer managed) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(managed.host, managed.port), config.socketTimeoutMillis);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Component message(String legacyText) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }

    private Config loadOrCreateConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("config.json");
            Gson gson = new GsonBuilder().setPrettyPrinting().create();

            if (!Files.exists(configPath)) {
                Config example = Config.example();
                Files.writeString(configPath, gson.toJson(example));
                logger.warning("No config.json found, created example configuration under: " + configPath);
                return example;
            }

            String json = Files.readString(configPath);
            return gson.fromJson(json, Config.class);
        } catch (IOException e) {
            throw new RuntimeException("Could not load configuration", e);
        }
    }

    /** /join – connects the player to the server that is currently ready for them. */
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
}