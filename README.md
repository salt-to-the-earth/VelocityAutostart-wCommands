# VelocityAutostart
> “**CraftyAutoServer**” → replaced with **VelocityAutostart**

Minimalist Velocity plugin: starts backend servers on demand via the Crafty Controller API and keeps the player in the lobby instead of simulating a Limbo fake world.
> *“Minimalistisches Velocity-Plugin: startet Backend-Server bei Bedarf über die Crafty-Controller-API und hält den Spieler solange in der Lobby…”*

## How it works

1. A player connects (login or `/server`) to a server listed in `config.json`.
2. The plugin checks via TCP connect whether the server is already running (`host:port`).
3. If not: the player stays in the lobby, the plugin calls `POST /api/v2/servers/{id}/action/start_server` on Crafty and then polls every few seconds via socket check whether the port is open.
4. As soon as it becomes reachable: automatic connection to the target server.
5. Timeout after `pollTimeoutSeconds` (default 120s) with an error message.
> *“Plugin prüft per TCP-Connect… pollt danach alle paar Sekunden… Timeout nach pollTimeoutSeconds…”*

No LimboAPI, no additional fake world, no repeated Crafty API calls during polling (only a simple socket connect) — therefore significantly more lightweight than LimboAutoServer.
> *“Kein LimboAPI… deutlich leichtgewichtiger…”*

---

## Build

```bash
./gradlew build
# Result: build/libs/velocity-autostart-1.0.0.jar
```

---

## Installation

1. Place the JAR into Velocity’s `plugins/` folder and restart Velocity.
2. It will automatically generate `plugins/velocity-autostart/config.json`:

```json
{
  "craftyBaseUrl": "https://crafty.example.com:8443",
  "craftyApiToken": "PUT_TOKEN_HERE",
  "craftyAllowInsecureTls": false,
  "lobbyServer": "lobby",
  "pollIntervalSeconds": 3,
  "pollTimeoutSeconds": 120,
  "socketTimeoutMillis": 500,
  "messageStarting": "&e%server% is starting, you will be automatically connected once it is ready...",
  "messageTimeout": "&cThe server could not be started in time. Please try again later.",
  "servers": {
    "survival": {
      "craftyServerId": "5adcec83-684c-4555-a7e9-9d913203d07e",
      "host": "127.0.0.1",
      "port": 25566
    }
  }
}
```

3. `craftyApiToken`: Crafty → Profile → API Keys → Generate Key. The key needs at least the `command` permission for the affected servers.
4. `craftyServerId`: from the URL in Crafty (`.../server/detail/<UUID>`) or via `GET /api/v2/servers`.
5. `host`/`port`: the address under which Velocity reaches the backend server (not the Crafty URL!).
6. Reload Velocity or restart the server so the configuration takes effect.
> *“host/port: die Adresse, unter der Velocity den Backend-Server erreicht…”*

---

## Requirements

- A lobby server already running, referenced as `lobbyServer`.
- Java 17+, Velocity 3.4+.
- Crafty API token with start permission for the managed servers.
> *“Java 17+, Velocity 3.4+.”*

---

## Limitations (intentionally kept simple)

- No automatic stopping on inactivity (can be added easily if needed, e.g., via scheduled task + `stop_server` action + player count check).
- No multi‑proxy synchronization of `startingServers` (only relevant with multiple Velocity instances).
- TLS verification can be disabled via `craftyAllowInsecureTls` if Crafty runs without a valid certificate — for production, better place a reverse proxy with a proper certificate in front.
> *“TLS-Verifizierung kann per craftyAllowInsecureTls deaktiviert werden…”*