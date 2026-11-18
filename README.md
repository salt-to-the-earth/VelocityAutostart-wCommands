# VelocityAutostart + HubControl

On-demand backend startup, idle shutdown, and in-game server controls for a Minecraft network. The repository builds two plugins: VelocityAutostart for the Velocity proxy and HubControl for the Paper Hub.

## One server configuration location

Velocity's `velocity.toml` is the source of truth for server names and backend addresses. Add Crafty Controller IDs in a separate top-level `[autostart]` table in the same file:

```toml
[servers]
hub = "127.0.0.1:25566"
survival = "127.0.0.1:25567"
levelup = "127.0.0.1:25568"
makeitbrakeit = "127.0.0.1:25569"
creative = "127.0.0.1:25570"

[autostart]
survival = "CRAFTY-ID-FOR-SURVIVAL"
levelup = "CRAFTY-ID-FOR-LEVELUP"
makeitbrakeit = "CRAFTY-ID-FOR-MAKEITBRAKEIT"
creative = "CRAFTY-ID-FOR-CREATIVE"
```

Important details:

- `[autostart]` is a separate top-level table. Do **not** name it `[servers.autostart]`; that would make it a nested value inside Velocity's `servers` table.
- The key must exactly match a server name in `[servers]`.
- The value is that server's Crafty Controller server ID.
- Do not normally add the always-on Hub/lobby to `[autostart]`. Add only backends this plugin should start and stop on demand.
- VelocityAutostart reads server addresses and ports from Velocity's registered server configuration. They are not duplicated in its JSON config.
- A server without an `[autostart]` ID continues to be a normal Velocity server; it simply is not managed by this plugin. Invalid or unregistered entries are logged and skipped.
- If an older installation has server mappings in VelocityAutostart's old `config.json` and no `[autostart]` table yet, the plugin migrates those Crafty IDs by appending the new table to `velocity.toml`. Existing TOML entries are preserved.

## Velocity proxy commands

VelocityAutostart registers:

```
/vas start survival
/vas stop survival
/vas status survival
```

- Status is public.
- Player-issued start/stop commands require proxy administrator status.
- Grant or revoke proxy administration from the Crafty Velocity console with `/op <online-player>` and `/deop <online-player>`. The target must be online so the plugin can store the player's UUID.
- The proxy console can also run start/stop commands.
- Stop is refused while players are connected, and new connections are blocked while shutdown is underway.
- `/join` connects a player to the backend for which they received a ready notification after startup.

Automatic startup on a player's attempt to connect to an offline managed backend, lobby redirection, clickable ready notification, and optional idle shutdown are retained.

## Paper Hub commands and command blocks

HubControl registers a separate command on the Paper Hub:

```
/hvas start survival
/hvas stop survival
/hvas status survival
```

- Hub OPs, the server console, and command blocks can start/stop managed servers.
- Ordinary players can check status but cannot start or stop servers.
- Command blocks do not need a permission attachment; authorization is handled inside HubControl.
- HubControl discovers managed server names from VelocityAutostart automatically. There is no second server list to maintain in the Hub config.

HubControl publishes a main-scoreboard objective named `vas_state` for command-block logic:

| Score | State |
|---:|---|
| 0 | STOPPED |
| 1 | RUNNING |
| 2 | STARTING |
| 3 | STOPPING |
| 4 | ERROR |

Each server is represented by a fake scoreboard holder with a leading dollar sign. Survival uses `$survival`.

For example, a command block can check whether Survival is running:

```
execute if score $survival vas_state matches 1 run <your command>
```

To request a start from a command block, enter `hvas start survival` as its command. To request a stop, enter `hvas stop survival`. The leading slash is optional in the command-block command field. If a command block appears not to run the command, first confirm `enable-command-block=true` in the Hub server's `server.properties` and test a vanilla command such as `say command-block-test`. HubControl logs the command-block world and coordinates when it receives an `/hvas` command, which distinguishes a Minecraft command-block setting problem from a plugin/API problem.

The scoreboard is the automation interface for portal logic. Portal block placement itself is intentionally outside these plugins.

## Control API and security

- VelocityAutostart exposes a small authenticated HTTP API on `127.0.0.1:28090` only. It is not exposed to the LAN.
- The API requires a random bearer token generated automatically in VelocityAutostart's config.
- HubControl copies that token from `controlApiToken` in `plugins/velocity-autostart/config.json`. Do not use the Crafty API token here.
- The Hub plugin does not receive Crafty credentials and cannot contact Crafty directly.
- The API provides managed-server discovery plus status/start/stop operations.
- The previous default API port 25590 is automatically migrated to 28090 by both plugins.

## Build

A single Gradle build produces two JAR files:

- `build/libs/velocity-autostart-1.2.1.jar`
- `hub-plugin/build/libs/hub-control-1.1.1.jar`

The project uses Java 25, Velocity API 4.3.2-SNAPSHOT, and Paper API 26.3.

## Installation overview

1. Install `velocity-autostart-1.2.1.jar` in Velocity's `plugins` directory.
2. Ensure `velocity.toml` contains the separate `[autostart]` table and the Crafty IDs for the backend servers you want managed.
3. Restart Velocity. The plugin reads the server addresses from `[servers]`, reads Crafty IDs from `[autostart]`, and generates/updates its config.
4. Copy `controlApiToken` from `plugins/velocity-autostart/config.json`.
5. Install `hub-control-1.1.1.jar` in the Hub Paper server's `plugins` directory.
6. In `plugins/HubControl/config.yml`, set `api-token` to the exact `controlApiToken` copied from VelocityAutostart.
7. Restart the Hub.

The Hub config contains only the API URL, API token, and refresh/request timing. It no longer contains a list of server names.

## HubControl configuration

```yaml
velocity-api-url: "http://127.0.0.1:28090"
api-token: "COPY_CONTROL_API_TOKEN_HERE"
status-refresh-seconds: 2
request-timeout-millis: 1500
```

## Compatibility and behavior

- Java 25 or later.
- Velocity API 4.3.2-SNAPSHOT for the proxy module.
- Paper API 26.3 for HubControl.
- Crafty Controller API v2.
- Managed backend ports must be reachable from the Velocity process.
- A server is reported as RUNNING when its configured TCP port is reachable from Velocity. STARTING and STOPPING are tracked during operations. A timeout or failed operation is reported as ERROR.
- Shutdown is guarded by Velocity's live connected-player count. If at least one player is connected, the stop request is rejected.
- TLS verification for the Crafty API remains controlled by `craftyAllowInsecureTls`. Disabling verification is not recommended for production.
