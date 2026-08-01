package org.manator.velocityautostart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin wrapper around the Crafty API v2. Only what we need: start a server.
 * No additional HTTP framework is needed, java.net.http suffices since Java 11.
 */
public class CraftyApiClient {

    private final Config config;
    private final Logger logger;
    private final HttpClient http;

    public CraftyApiClient(Config config, Logger logger) {
        this.config = config;
        this.logger = logger;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5));
        if (config.craftyAllowInsecureTls) {
            builder.sslContext(InsecureTls.create());
        }
        this.http = builder.build();
    }

    /**
     * Fires the start_server action call against Crafty. Asynchronous, does not block
     * the Velocity thread.
     */
    public void startServer(String craftyServerId) {
        String url = config.craftyBaseUrl + "/api/v2/servers/" + craftyServerId + "/action/start_server";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.craftyApiToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        logger.info("Crafty start command for server " + craftyServerId + " sent.");
                    } else {
                        logger.warning("Crafty start command failed (" + response.statusCode()
                                + "): " + response.body());
                    }
                })
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Error calling Crafty API", ex);
                    return null;
                });
    }

    /**
     * Fires the stop_server action call against Crafty. Asynchronous, does not block
     * the Velocity thread.
     */
    public void stopServer(String craftyServerId) {
        String url = config.craftyBaseUrl + "/api/v2/servers/" + craftyServerId + "/action/stop_server";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + config.craftyApiToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        logger.info("Crafty stop command for server " + craftyServerId + " sent (idle timeout).");
                    } else {
                        logger.warning("Crafty stop command failed (" + response.statusCode()
                                + "): " + response.body());
                    }
                })
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "Error calling Crafty API (stop)", ex);
                    return null;
                });
    }

    /** Allows self-signed certificates if Crafty runs without a reverse proxy/TLS certificate. */
    private static class InsecureTls {
        static javax.net.ssl.SSLContext create() {
            try {
                javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509ExtendedTrustManager() {
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, java.net.Socket s) {}
                            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) {}
                        }
                };
                javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
                ctx.init(null, trustAll, new java.security.SecureRandom());
                return ctx;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}