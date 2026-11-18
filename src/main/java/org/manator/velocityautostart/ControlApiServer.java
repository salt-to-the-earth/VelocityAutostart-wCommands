package org.manator.velocityautostart;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small authenticated HTTP bridge for the Paper HubControl plugin.
 * It binds only to IPv4 loopback; it is not exposed to the LAN.
 */
public final class ControlApiServer implements AutoCloseable {

    private final VelocityAutostartPlugin plugin;
    private final String token;
    private final Gson gson = new Gson();
    private final ExecutorService clients = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "VelocityAutostart-ControlAPI");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;

    public ControlApiServer(VelocityAutostartPlugin plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start(int port) throws IOException {
        listener = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = listener.accept();
                    clients.execute(() -> handle(socket));
                } catch (IOException exception) {
                    if (running) {
                        plugin.getLogger().warning("Control API accept failed: " + exception.getMessage());
                    }
                }
            }
        }, "VelocityAutostart-ControlAPI-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream()) {
            socket.setSoTimeout(3000);
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isBlank()) {
                return;
            }
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                writeResponse(output, 400, "ERROR", "Malformed HTTP request", 0);
                return;
            }

            String authorization = null;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0 && "Authorization".equalsIgnoreCase(header.substring(0, colon).trim())) {
                    authorization = header.substring(colon + 1).trim();
                }
            }
            if (!authorized(authorization)) {
                writeResponse(output, 401, "ERROR", "Unauthorized", 0);
                return;
            }

            String[] pathParts = requestParts[1].split("/");
            if (pathParts.length != 6 || !"api".equals(pathParts[1]) || !"v1".equals(pathParts[2])
                    || !"servers".equals(pathParts[3])) {
                writeResponse(output, 404, "ERROR", "Unknown endpoint", 0);
                return;
            }

            String serverName = pathParts[4];
            String action = pathParts[5];
            String method = requestParts[0];

            if ("status".equals(action) && "GET".equals(method)) {
                VelocityAutostartPlugin.ServerSnapshot snapshot = plugin.getServerSnapshot(serverName);
                if (snapshot == null) {
                    writeResponse(output, 404, "ERROR", "Server is not managed", 0);
                } else {
                    writeResponse(output, 200, snapshot.status, snapshot.message, snapshot.players);
                }
                return;
            }
            if ("start".equals(action) && "POST".equals(method)) {
                VelocityAutostartPlugin.ControlResult result = plugin.requestStart(serverName);
                writeResponse(output, result.httpCode, result.status, result.message, result.players);
                return;
            }
            if ("stop".equals(action) && "POST".equals(method)) {
                VelocityAutostartPlugin.ControlResult result = plugin.requestStop(serverName);
                writeResponse(output, result.httpCode, result.status, result.message, result.players);
                return;
            }
            writeResponse(output, 405, "ERROR", "Method or action not supported", 0);
        } catch (Exception exception) {
            plugin.getLogger().warning("Control API request failed: " + exception.getMessage());
        }
    }

    private boolean authorized(String supplied) {
        if (supplied == null || !supplied.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        byte[] actual = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private void writeResponse(OutputStream output, int code, String status, String message, int players)
            throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        body.addProperty("message", message);
        body.addProperty("players", players);
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        String reason = switch (code) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            default -> "Internal Server Error";
        };
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + bytes.length + "\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (listener != null) {
                listener.close();
            }
        } catch (IOException ignored) {
        }
        clients.shutdownNow();
    }
}
