package org.manator.velocityautostart;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple POJO configuration, stored as config.json in the plugin data directory.
 */
public class Config {

    public String craftyBaseUrl = "https://crafty.example.com:8443";
    public String craftyApiToken = "PUT_TOKEN_HERE";
    public boolean craftyAllowInsecureTls = false;

    public String lobbyServer = "default_lobby";
    public int pollIntervalSeconds = 3;
    public int pollTimeoutSeconds = 120;
    public int socketTimeoutMillis = 500;

    public String messageStarting = "&e%server% is starting, you'll be notified once it's ready...";
    public String messageReady = "&a%server% is ready!";
    public String messageTimeout = "&cThe server could not be started in time. Please try again later.";
    public String messageJoinButtonText = "[Click to join]";
    public String messageJoinHoverText = "Click to join %server%";
    public String messagePlayerOnly = "&cOnly players can use this command.";
    public String messageNoServerReady = "&cNo server ready to join yet. Wait for the start notification.";
    public String messageServerNotFound = "&cServer not found.";
    public String messageServerStoppedIdle = "&c%server% was stopped due to inactivity. Reconnect to start it again.";

    // Authenticated loopback API used by the Paper HubControl plugin.
    public int controlApiPort = 25590;
    public String controlApiToken = "";

    // Auto-Stop on Inactivity
    public boolean autoStopEnabled = true;
    public int autoStopIdleSeconds = 600;
    public int autoStopCheckIntervalSeconds = 30;
    public int autoStopGracePeriodSeconds = 120;

    public Map<String, ManagedServer> servers = new HashMap<>();

    public static class ManagedServer {
        public String craftyServerId;
        public String host;
        public int port;

        public ManagedServer() {
        }

        public ManagedServer(String craftyServerId, String host, int port) {
            this.craftyServerId = craftyServerId;
            this.host = host;
            this.port = port;
        }
    }

    public static Config example() {
        return new Config();
    }
}
