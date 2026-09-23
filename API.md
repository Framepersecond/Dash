# Dash Read-Only API

Dash exposes the same **read-only** view of your server two ways:

| Surface | For | Transport |
|---|---|---|
| `DashReadApi` | plugins and mods on the same server | in-process Java call |
| `GET /api/v1/…` | anything else — scripts, dashboards, bots, other languages | HTTP + JSON |

Both are read-only by construction. There is no write operation in the interface,
no mutating HTTP verb is accepted, and no scope exists that could grant one.

---

## 1. In-process API (plugins and mods)

Works identically on Dash (Bukkit/Paper), FabricDash (Fabric) and ForgeDash
(NeoForge). Nothing needs to be enabled, and no key is involved — the caller is
already running on the server.

```java
import dash.api.*;

DashApiProvider.get().ifPresent(api -> {
    ServerSnapshot server = api.server();
    getLogger().info(server.onlinePlayers() + "/" + server.maxPlayers() + " online");

    for (PlayerSnapshot player : api.players()) {
        getLogger().info(player.name() + " is in " + player.world());
    }
});
```

`get()` returns empty while Dash is absent, still starting, or shutting down, so
always handle that rather than assuming the API is there.

On Bukkit the service is additionally registered with the `ServicesManager`, if
you prefer the platform-idiomatic lookup:

```java
RegisteredServiceProvider<DashReadApi> rsp =
        Bukkit.getServicesManager().getRegistration(DashReadApi.class);
DashReadApi api = rsp == null ? null : rsp.getProvider();
```

### Compiling against it

Add the Dash jar as a *provided* / *compileOnly* dependency — it is already on
the server at runtime:

```xml
<dependency>
  <groupId>dash</groupId>
  <artifactId>dash</artifactId>
  <version>4.5</version>
  <scope>provided</scope>
</dependency>
```

```groovy
compileOnly files("libs/dash-4.5.jar")
```

Only the `dash.api` package is a stable surface. `DashReadApi.API_VERSION` is
`"1"` and changes only on a breaking change, so guard against future majors if
you want to be strict.

### What you get

| Method | Returns |
|---|---|
| `server()` | platform, software + Minecraft version, Dash version, player counts, uptime, whitelist state |
| `performance()` | TPS, MSPT, heap, CPU load, loaded chunks, entity count |
| `performanceHistory(int)` | past samples, oldest first |
| `players()` | every online player |
| `player(UUID)` / `playerByName(String)` | one player, online or known offline |
| `worlds()` | loaded worlds with chunk, entity and player counts |
| `extensions()` | plugins (Bukkit) or mods (Fabric/NeoForge) alongside Dash |

Returned collections are immutable snapshots.

---

## 2. HTTP API

### Enable it

It is **off by default**. Upgrading Dash never opens it on its own.

```yaml
# config.yml
api:
  enabled: true
  rate-limit-per-minute: 120
```

The toggle is re-read per request, so it takes effect without a restart.

### Create a key

```
/dash api create <label> [scopes] [days]
/dash api list
/dash api revoke <key-id>
```

Operator/console only. Examples:

```
/dash api create grafana performance:read,server:read 90
/dash api create my-bot *
```

The token is **written to `api-key-<id>.txt`** in the Dash data folder, not
printed to chat or console — console output ends up in `latest.log`, which is
routinely pasted into support threads. Copy the token out of that file, then
delete it. Dash stores only a SHA-256 hash and cannot show you the token again.

### Call it

```bash
curl -H "Authorization: Bearer dash_ak_XXXX.YYYY" \
     http://your-server:8080/api/v1/server
```

| Endpoint | Scope | Returns |
|---|---|---|
| `GET /api/v1` | any | version, your scopes, endpoint list |
| `GET /api/v1/server` | `server:read` | server identity and counts |
| `GET /api/v1/performance` | `performance:read` | current sample |
| `GET /api/v1/performance/history?points=60` | `performance:read` | up to 1440 samples |
| `GET /api/v1/players` | `players:read` | online players |
| `GET /api/v1/players/{uuid\|name}` | `players:read` | one player |
| `GET /api/v1/worlds` | `worlds:read` | loaded worlds |
| `GET /api/v1/extensions` | `extensions:read` | plugins or mods |

Example:

```json
{
  "platform": "bukkit",
  "softwareName": "Paper",
  "minecraftVersion": "1.21.8",
  "dashVersion": "4.5",
  "onlinePlayers": 3,
  "maxPlayers": 20,
  "uptimeMillis": 86400000,
  "whitelistEnabled": true
}
```

### Status codes

| Code | Meaning |
|---|---|
| 200 | fine |
| 401 | missing, malformed, unknown, revoked or expired key |
| 403 | the key is valid but lacks the scope for this endpoint |
| 404 | API disabled, or unknown endpoint/player |
| 405 | you used anything other than GET or HEAD |
| 429 | rate limit hit — see `Retry-After` |

---

## 3. Security

What the design commits to, and why:

- **Off unless you turn it on.** A disabled API answers 404 and never inspects
  the `Authorization` header.
- **Read-only at the transport layer.** Only GET and HEAD reach any logic. The
  handler holds a `DashReadApi`, which has no mutating method to call, so there
  is no write path to reach even by accident.
- **Keys are their own credential.** They are not panel sessions and not the
  NeoDash bridge secret, and they work on no route outside `/api/v1`. A leaked
  read key cannot be replayed against the admin UI.
- **Hashed at rest.** Only `SHA-256(secret)` is stored, in an owner-only file
  written atomically. Verification is an O(1) lookup by the public key id plus
  one constant-time comparison.
- **Least privilege.** Each key carries an explicit scope set; anything not
  listed is denied. Keys can expire and can be revoked.
- **Rate limited** per key *and* per client address, before any hashing, so an
  unauthenticated flood cannot be used as a CPU amplifier. The limiter uses the
  socket address, never `X-Forwarded-For`, which a caller controls.
- **No browser cross-origin access.** No CORS headers are emitted, so a
  malicious page cannot read your API using a victim's network position.
  Responses are `no-store`.
- **Nothing sensitive in the payload.** No secrets, no tokens, no file paths,
  **no world seeds** (they would reveal every structure) and **no player IP
  addresses**.
- **Audited.** Scope denials and key creation/revocation go to the audit log.

The guarantees above are enforced by `ReadApiSecurityTest`, which runs against a
real HTTP server and a real key store on every build.
