# Dash

**Version 4.5.1 - Minecraft 26.3 support**

Dash is a self-hosted control surface for one Minecraft server. It combines live operations, player care, files, backups, plugins, Guardian investigations and a new evidence-driven Intelligence Center without giving up direct Paper-side control.

[![Version](https://img.shields.io/badge/version-4.5.1-22d3ee)](#release-status)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x%20%7C%2026.2%20%7C%2026.3-22c55e)](#requirements)
[![Java](https://img.shields.io/badge/Java-21%2B-f97316)](#requirements)
[![License](https://img.shields.io/badge/license-BSD--3--Clause-blue)](#license)

## The 4.3 Intelligence Center

Seven focused workspaces connect diagnosis, recovery and daily operation. They are backed by SQLite state, server-side authorization and real action handlers rather than display-only controls.

| Workspace | What it gives operators |
|---|---|
| Investigate | Shadow Boot Lab, Root Cause Explorer, Performance Regression Radar, Plugin Resource Attribution, Guided Safe Mode and Dependency and Impact Map. |
| Change | State Time Machine, Backup Content Explorer, Adaptive Maintenance Window and Action Guardrails. |
| Players | Player Journey Replay, Support and Appeals Inbox, Cross-Server Player Identity and Player Experience Score. |
| Policy | Just-in-Time Staff Access, Dual-Control Actions and Retention Center. |
| Supply | Supply Chain Center, Config Schema Assistant and Universal Search. |
| Reliability | World Health Center, Storage Intelligence and Service Level Dashboard. |
| Response | Operator War Room and a configurable Public Status Page. |

### Recovery with evidence

- Boot a copied configuration and plugin inventory in an isolated Shadow Boot workspace without linking back to live files.
- Correlate logs, recent configuration changes and plugin artifacts in Root Cause Explorer.
- Capture state, compare exact changes and restore through a staged, integrity-verified transaction with a safety snapshot and rollback path.
- Browse backup contents before restore and inspect dependency impact before changing a plugin.
- Detect TPS, MSPT and memory regressions against retained performance samples.

### Safer administration

- Require a reason, quiet hours, verified backups or approval for matching high-risk actions.
- Grant narrow staff permissions for a bounded time and revoke them automatically.
- Route sensitive operations through approve/reject dual control.
- Validate editable configuration scalars against inferred types, port ranges and concurrent file changes.
- Inspect artifact hashes, duplicate versions, signatures and dependency risk in the Supply Chain Center.

### Player and service care

- Replay joins, commands, moderation and support events as one player journey.
- Track appeals and support conversations with validated type and status workflows.
- Search literal player identities safely, including names containing `%` or `_`.
- Combine availability, latency, incidents and player signals into experience and service-level views.
- Coordinate incidents in durable war rooms and publish component health without exposing the admin dashboard.

## Complete Dashboard

| Area | Capability |
|---|---|
| Live operations | TPS, memory, CPU, uptime, online players, console stream and authorized command dispatch. |
| Players | Profiles, sessions, notes, freeze, kick, ban, teleport, whitelist and inventory tools. |
| Guardian | Cases, evidence, block and container history, incidents, suspicion scores, rollback previews and CoreProtect import. |
| Files | Whole-row navigation, upload and folder upload, edit, rename, download and guarded delete. |
| Backups | Verified manual backups, schedules, retention, restore points, download and delete controls. |
| Plugins | Installed plugin view, upload workflow, Modrinth browser, inventory scan and update hints. |
| Intelligence | Diagnosis, guarded changes, recovery, player care, supply-chain checks and coordinated incident response. |
| Staff and delivery | Tickets, notes, notifications, Discord delivery checks, graphs and audit-friendly activity. |
| NeoDash bridge | Signed SSO, approval-aware bridge users, TLS-aware return navigation and restart routing through NeoDash. |

## Evolution Since 3.1

- **4.0:** rebuilt route and content motion, shortened Guardian with paged activity, made file rows fully clickable, kept uploads in place and improved GitHub-backed updates and NeoDash handoff.
- **4.1:** completed exposed beta workflows, fixed the Paper response content type that could show raw HTML, preserved sessions across refresh, hardened Doctor and backup paths, and verified every visible selection.
- **4.2:** added the durable Operations Center with maintenance plans, incident response, shift handover, restore drills, drift, capacity forecasts, compatibility checks, permission simulation and executable recipes.
- **4.3:** adds the 25-part Intelligence Center, generic action guardrails, transactional state recovery, public service health, deeper security validation and a smoother response-aligned animation layer.
- **4.4:** supports every Minecraft 1.21.x release and 26.2 from one Paper plugin, removes the redundant Operations tab and focuses advanced workflows in Intelligence.

## Installation

1. Download `dash-4.4.jar` from the release page.
2. Put it in the server's `plugins` folder.
3. Restart once to create the configuration.
4. Configure `plugins/Dash/config.yml` when a different port or public panel URL is needed.
5. Run `/dash register` as an operator and complete the one-time setup link.

```yaml
port: 8080

database:
  type: sqlite
  file: dash.db

backups:
  directory: backups
  max-backups: 10
```


## Hosting behind a reverse proxy

Set `base-path` when the panel is served below a sub-path, for example `/admin`
for `https://example.org/admin/`. Every link, form target and asset URL is then
emitted with that prefix. Configure the proxy to strip the prefix before
forwarding — `proxy_pass http://127.0.0.1:8080/` with the trailing slash in
nginx, or Traefik's `StripPrefix` middleware. When `base-path` is left blank, an
`X-Forwarded-Prefix` header sent by the proxy is honoured instead. Forward
`Host` (or `X-Forwarded-Host`) and `X-Forwarded-Proto` so the same-origin checks
see the browser-facing address.

## Security Model

- Authenticated cookies, CSRF checks, bounded request bodies and server-side permission checks protect state-changing routes.
- Registration and handoff tokens are short-lived, replay resistant and generated with secure randomness.
- File, archive and crash-report paths are canonicalized and protected against traversal and symlink escape.
- Downloads and updates enforce allowlists, redirect checks, size limits and digest verification where release metadata provides it.
- High-risk Intelligence actions support reason challenges, just-in-time rights, quiet hours and dual approval.
- Audit records capture actor and source context without logging bridge secrets or passwords.

Run Dash behind TLS or a trusted reverse proxy and limit it to staff networks.

## Requirements

- Minecraft 1.21.x or 26.2
- Paper, Spigot, Purpur, Bukkit or a compatible fork
- Java 21 or newer

## Release Status

Current release: **Dash 4.5.1**

See [RELEASE_NOTES.md](./RELEASE_NOTES.md) for the full changelog and [API.md](./API.md) for the read-only API.

## License

BSD 3-Clause. See `LICENSE` for details.
