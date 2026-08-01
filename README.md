# CraftyAutoServer

Minimalistisches Velocity-Plugin: startet Backend-Server bei Bedarf über die Crafty-Controller-API
und hält den Spieler solange in der Lobby, statt eine Limbo-Fake-Welt zu simulieren.

## Funktionsweise

1. Spieler verbindet sich (Login oder `/server`) mit einem in `config.json` gelisteten Server.
2. Plugin prüft per TCP-Connect, ob der Server bereits läuft (`host:port`).
3. Falls nicht: Spieler bleibt in der Lobby, Plugin ruft `POST /api/v2/servers/{id}/action/start_server`
   bei Crafty auf und pollt danach alle paar Sekunden per Socket-Check, ob der Port offen ist.
4. Sobald erreichbar: automatischer Connect zum Zielserver.
5. Timeout nach `pollTimeoutSeconds` (Default 120s) mit Fehlermeldung.

Kein LimboAPI, keine zusätzliche Fake-World, kein wiederholter Crafty-API-Call beim Pollen
(nur ein simpler Socket-Connect) — daher deutlich leichtgewichtiger als LimboAutoServer.

## Build

```bash
./gradlew build
# Ergebnis: build/libs/crafty-autoserver-1.0.0.jar
```

## Installation

1. JAR in den `plugins/`-Ordner von Velocity legen, Velocity neu starten.
2. Es wird automatisch `plugins/crafty-autoserver/config.json` erzeugt:

```json
{
  "craftyBaseUrl": "https://crafty.example.com:8443",
  "craftyApiToken": "PUT_TOKEN_HERE",
  "craftyAllowInsecureTls": false,
  "lobbyServer": "lobby",
  "pollIntervalSeconds": 3,
  "pollTimeoutSeconds": 120,
  "socketTimeoutMillis": 500,
  "messageStarting": "&e%server% wird gestartet, du wirst automatisch verbunden sobald er bereit ist...",
  "messageTimeout": "&cDer Server konnte nicht rechtzeitig gestartet werden. Versuch es später erneut.",
  "servers": {
    "survival": {
      "craftyServerId": "5adcec83-684c-4555-a7e9-9d913203d07e",
      "host": "127.0.0.1",
      "port": 25566
    }
  }
}
```

3. `craftyApiToken`: Crafty → Profil → API Keys → Generate Key. Der Key braucht mindestens
   `command`-Permission auf den betroffenen Servern.
4. `craftyServerId`: aus der URL in Crafty (`.../server/detail/<UUID>`) oder via `GET /api/v2/servers`.
5. `host`/`port`: die Adresse, unter der Velocity den Backend-Server erreicht (nicht die Crafty-URL!).
6. Velocity neu laden bzw. Server neu starten, damit die Konfiguration greift.

## Voraussetzungen

- Ein bereits laufender Lobby-Server, der als `lobbyServer` referenziert wird.
- Java 17+, Velocity 3.4+.
- Crafty-API-Token mit Start-Berechtigung für die verwalteten Server.

## Einschränkungen (bewusst simpel gehalten)

- Kein automatisches Stoppen bei Inaktivität (kann bei Bedarf leicht ergänzt werden,
  z.B. per Scheduled Task + `stop_server`-Action + Playercount-Check).
- Keine Multi-Proxy-Synchronisierung von `startingServers` (nur relevant bei mehreren Velocity-Instanzen).
- TLS-Verifizierung kann per `craftyAllowInsecureTls` deaktiviert werden, falls Crafty
  ohne gültiges Zertifikat läuft — für Produktivbetrieb besser einen Reverse-Proxy mit echtem
  Zertifikat davorsetzen.
