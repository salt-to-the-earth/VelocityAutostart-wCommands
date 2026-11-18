# VelocityAutostart + HubControl

A two-plugin Minecraft network control project. VelocityAutostart runs on the Velocity proxy and manages backend servers through the Crafty Controller API. HubControl runs on the Paper Hub server and gives command blocks a secure way to request those controls.

The project builds **two separate JAR files** from one GitHub repository.

## Features retained from VelocityAutostart

- Starts managed backend servers on demand when a player attempts to connect.
- Keeps players in the lobby while a backend starts.
- Offers a clickable join message and `/join` when the backend becomes reachable.
- Optionally stops servers after an idle period.
- Uses Crafty Controller API for backend start and stop actions.

## Hub command-block control

HubControl communicates with the Velocity plugin through an authenticated HTTP API bound only to `127.0.0.1`. The Hub plugin does not receive the Crafty API token and cannot contact Crafty directly.

Hub command syntax:

```
/vas start survival
/vas stop survival
/vas status survival
```

- `start` requests startup and reports that the request was accepted.
- `stop` refuses to shut down a server while any players are connected. Velocity also blocks new connection attempts while a shutdown is in progress.
- `status` reports the latest server state and connected player count.

The status cache is refreshed every two seconds by default. HubControl also publishes a main-scoreboard objective named `vas_state` for command-block logic:

| Score | State |
|---:|---|
| 0 | STOPPED |
| 1 | RUNNING |
| 2 | STARTING |
| 3 | STOPPING |
| 4 | ERROR |

Each server is represented by a fake scoreboard holder named with a leading dollar sign. For example, Survival uses `$survival`.

Command blocks can test whether Survival is running with:

```
execute if score $survival vas_state matches 1 run <your command>
```

This scoreboard is the automation interface. Command blocks can use it to decide when to place or remove portal blocks; portal block placement itself is intentionally outside both plugins.

## Security

- The control API listens only on IPv4 loopback (`127.0.0.1`), not on the LAN.
- Every request requires a random bearer token.
- VelocityAutostart generates the token automatically in its config if one is not already present.
- HubControl is configured with the same token.
- Hub command blocks and the server console may request start/stop operations. Ordinary players need the `hubcontrol.admin` permission to do so.
- The Velocity chat command `/vas status <server>` is public; `/vas start` and `/vas stop` require `velocityautostart.admin` for players.

## Build

The root Gradle project includes both modules. A single build produces two JARs:

- `build/libs/velocity-autostart-1.1.0.jar`
- `hub-plugin/build/libs/hub-control-1.0.0.jar`

The project uses Java 25, the current Velocity API, and the Paper 26.3 API.

## Installation overview

1. Install `velocity-autostart-1.1.0.jar` in Velocity's `plugins` directory.
2. Restart Velocity once so it creates or updates `plugins/velocity-autostart/config.json`.
3. Copy the generated `controlApiToken` value from that file.
4. Install `hub-control-1.0.0.jar` in the Hub Paper server's `plugins` directory.
5. In `plugins/HubControl/config.yml`, set `api-token` to the exact `controlApiToken` copied from VelocityAutostart.
6. Ensure `managed-servers` lists the Velocity server names to expose to command blocks, such as `survival`.
7. Restart Hub.

Do not put the Crafty API token into HubControl. Only VelocityAutostart needs it.

## VelocityAutostart configuration additions

Existing settings remain in `plugins/velocity-autostart/config.json`. The plugin adds:

```json
{
  "controlApiPort": 25590,
  "controlApiToken": "generated-automatically"
}
```

Keep the generated token private. The API is intended for HubControl on the same Ubuntu VM.

## HubControl configuration

```yaml
velocity-api-url: "http://127.0.0.1:25590"
api-token: "COPY_CONTROL_API_TOKEN_HERE"
managed-servers:
  - "survival"
status-refresh-seconds: 2
request-timeout-millis: 1500
```

Server names must match the names in Velocity's `[servers]` section and the keys in VelocityAutostart's `servers` object.

## Compatibility

- Java 25 or later.
- Velocity API 4.3.2-SNAPSHOT for the proxy module.
- Paper API 26.3 for HubControl.
- Crafty Controller API v2.
- The managed backend ports must be reachable from the Velocity process.

## Important behavior

A server is reported as `RUNNING` when its configured TCP port is reachable from Velocity. `STARTING` and `STOPPING` are tracked while the corresponding operation is in progress. A timeout or failed operation is reported as `ERROR`.

Shutdown is guarded by Velocity's live connected-player count. If at least one player is connected, the stop request is rejected. While stopping, new connections to that backend are denied until its port closes.

TLS verification for the Crafty API remains controlled by `craftyAllowInsecureTls`. Disabling verification is not recommended for production.
