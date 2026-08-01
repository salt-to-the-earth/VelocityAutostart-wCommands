# VelocityAutostart

Minimalist Velocity plugin: automatically starts backend servers on demand via the Crafty Controller API, keeps players in the lobby until the server is ready, and optionally **stops servers again when they become idle**.

No LimboAPI, no fake worlds, no heavy dependencies — just lightweight socket checks and Crafty API calls.

---

## Features

### On‑Demand Server Startup
- When a player connects (login or `/server`) to a managed backend server, VelocityAutostart checks via TCP whether the server is already running.
- If not running:
    - Player is redirected to the lobby.
    - Plugin triggers `start_server` via Crafty API.
    - Plugin polls the server port until it becomes reachable.
    - Player receives a clickable “Join” message once the server is ready.

### Click‑to‑Join System
- When the backend server becomes reachable, the player receives:
    - A “server ready” message
    - A clickable button (`[Click to join]`)
    - A hover tooltip (“Click to join %server%”)
- The player can also use `/join`.

### Timeout Handling
- If the server does not start within `pollTimeoutSeconds`, the player receives a timeout message.

### Automatic Stop on Inactivity (NEW)
VelocityAutostart now includes **Auto‑Stop**, based on your new classes:

- If a server has **no players online** for `autoStopIdleSeconds`, it is stopped via Crafty API.
- A **grace period** (`autoStopGracePeriodSeconds`) prevents immediate shutdown after startup.
- Idle checks run every `autoStopCheckIntervalSeconds`.
- Players who still have a pending `/join` for that server are notified that the server was stopped.

### Optional Insecure TLS
- If Crafty runs without a valid certificate, TLS verification can be disabled via `craftyAllowInsecureTls`.

---

## How it works

1. Player connects to a managed server.
2. VelocityAutostart checks reachability via TCP (`host:port`).
3. If offline:
    - Player is redirected to the lobby.
    - Plugin triggers Crafty `start_server`.
    - Plugin polls the port every `pollIntervalSeconds`.
4. When reachable:
    - Player receives a clickable join message.
5. If idle later:
    - Plugin triggers Crafty `stop_server`.

---

## Build

```bash
./gradlew build
# Result: build/libs/velocity-autostart-1.0.0.jar
```

---

## Installation

1. Place the JAR into Velocity’s `plugins/` folder.
2. Restart Velocity.
3. A default config will be created at:

```
plugins/velocity-autostart/config.json
```

### Example configuration

```json
{
  "craftyBaseUrl": "https://crafty.example.com:8443",
  "craftyApiToken": "PUT_TOKEN_HERE",
  "craftyAllowInsecureTls": false,

  "lobbyServer": "default_lobby",
  "pollIntervalSeconds": 3,
  "pollTimeoutSeconds": 120,
  "socketTimeoutMillis": 500,

  "messageStarting": "&e%server% is starting, you'll be notified once it's ready...",
  "messageReady": "&a%server% is ready!",
  "messageTimeout": "&cThe server could not be started in time. Please try again later.",
  "messageJoinButtonText": "[Click to join]",
  "messageJoinHoverText": "Click to join %server%",
  "messagePlayerOnly": "&cOnly players can use this command.",
  "messageNoServerReady": "&cNo server ready to join yet. Wait for the start notification.",
  "messageServerNotFound": "&cServer not found.",
  "messageServerStoppedIdle": "&c%server% was stopped due to inactivity. Reconnect to start it again.",

  "autoStopEnabled": true,
  "autoStopIdleSeconds": 600,
  "autoStopCheckIntervalSeconds": 30,
  "autoStopGracePeriodSeconds": 120,

  "servers": {
    "survival": {
      "craftyServerId": "5adcec83-684c-4555-a7e9-9d913203d07e",
      "host": "127.0.0.1",
      "port": 25566
    }
  }
}
```

---

## Requirements

- A running lobby server (`lobbyServer`).
- Java 17+, Velocity 3.4+.
- Crafty API token with `command` permission for the managed servers.

---

## Limitations

- No multi‑proxy synchronization of `startingServers`.
- Auto‑Stop is simple and does not track cross‑proxy player counts.
- TLS verification can be disabled, but production setups should use a proper certificate.