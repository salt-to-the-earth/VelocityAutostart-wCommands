package org.manator.velocityautostart;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple POJO configuration, stored as config.json in the plugin data directory.
 */
public class Config {

    public String craftyBaseUrl = "https://crafty.example.com:8443";
    public String craftyApiToken = "PUT_TOKEN_HERE";
    public boolean craftyAllowInsecureTls = false; // true = accept self-signed certificates

    public String lobbyServer = "default_lobby";
    public int pollIntervalSeconds = 3;
    public int pollTimeoutSeconds = 120; // timeout after this time
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

    // Auto-Stop on Inactivity
    public boolean autoStopEnabled = true;
    public int autoStopIdleSeconds = 600;          // server will be stopped if no players are online for this long
    public int autoStopCheckIntervalSeconds = 30;  // how often it is checked
    public int autoStopGracePeriodSeconds = 120;   // minimum runtime after which Auto-Stop can act

    public Map<String, ManagedServer> servers = new HashMap<>();

    /** Represents a backend server managed by Crafty. */
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

    /** Provides an example configuration if none is available. */
    public static Config example() {
        Config c = new Config();
        c.servers.put("example.org", new ManagedServer(
                "5adcec83-684c-4555-a7e9-9d913203d07e", "127.0.0.1", 25566));
        return c;
    }
}