# Dash

<div align="center">

**Modern web-based admin dashboard for Paper/Bukkit-family Minecraft servers**

Manage, monitor, moderate, update, and maintain your Minecraft server from any device with a browser.

[![Release](https://img.shields.io/badge/release-v4.0-10b981?style=for-the-badge)](../../releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-62b47a?style=for-the-badge)](#supported-versions)
[![Paper](https://img.shields.io/badge/Paper%20%2F%20Bukkit-supported-22c55e?style=for-the-badge)](#supported-versions)
[![Java](https://img.shields.io/badge/Java-21%2B-f89820?style=for-the-badge)](#supported-versions)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue?style=for-the-badge)](LICENSE)

</div>

---

## Overview

Dash is a powerful web administration panel for Paper, Spigot, Bukkit, and compatible Minecraft servers. It gives server owners and staff a clean, responsive operations surface for monitoring performance, managing players, running console commands, browsing files, maintaining plugins, reviewing Guardian activity, handling backups, and applying GitHub-backed updates without needing direct terminal access for every task.

Dash keeps the familiar single-server workflow while making day-to-day operations smoother: secure setup, staff tools, local plugin maintenance, notifications, graph snapshots, Guardian investigation tools, mobile-friendly layouts, and polished browser interactions for desktop, tablet, and mobile operators.

## Current Release

- **Latest version:** `v4.0`
- **Release date:** `2026-07-06`
- **Release:** available from the [GitHub releases page](../../releases)
- **License:** BSD 3-Clause

## What's New in Dash 4.0

Dash 4.0 is the major Paper/Bukkit-family release after 3.1. It turns the dashboard into a smoother, more complete operations surface while keeping the familiar single-server workflow.

### Motion and Interface Polish

- Rebuilt the shared dashboard animation layer around smoother route changes and content swaps.
- Added smoother card entrance timing, row reveals, hover lift, press feedback, status flips, and value flashes.
- Removed the bumpy pre-exit navigation feel that made page switches feel delayed or disconnected from user input.
- Kept reduced-motion behavior available for operators who prefer less animation.
- Tuned interaction feedback toward transform and opacity patterns for better browser performance.

### Guardian

- Shortened the Guardian activity area with a bounded table height and sticky headers.
- Added client-side paging for Timeline, Blocks, and Containers so Guardian no longer stretches into an extremely long page.
- Moved Guardian investigation tools out of the right-side tower into a full-width responsive tool grid.
- Kept Selection, Status, Insights, Cases, Notes, Rollback, Regions, Rules, Retention, Purge, and CoreProtect tools available with less scrolling.
- Added subtle row motion when paging or switching Guardian activity tabs.

### Files and Uploads

- File and folder rows now behave like clickable bubbles, so opening a folder no longer requires clicking only the filename.
- File-manager actions keep the existing guarded path handling for protected server runtime files.
- Upload, edit, rename, download, and delete flows remain inside the browser experience without forcing unnecessary navigation.

### GitHub and Update Workflows

- Expanded GitHub-backed update checks and release visibility for Dash.
- Preserved staged update behavior so downloads can be applied cleanly on restart.
- Kept rate-limit-aware update scanning and manual retry behavior.

### Maintenance and Staff Tools

- Continued the local maintenance split: Paper plugin work belongs in Dash, while fleet orchestration belongs in NeoDash.
- Kept Advanced Backups, Plugin Manager, Plugin Browser, Dash Doctor, Staff, Notifications, and Graphs as plugin-local tools.
- Improved the release narrative around Modrinth search, startup inventory scans, plugin update hints, and local server diagnostics.

### NeoDash Bridge

- Restart actions from NeoDash SSO sessions delegate back to NeoDash's configured start path.
- Restart now routes into the NeoDash startup-log flow so operators can watch the server come back online.
- Bridge sessions keep the master dashboard URL and restart callback for future lifecycle actions.

### Release Metadata

- Maven project version updated to `4.0`.
- `plugin.yml` version updated to `4.0`.

## Features

### Real-Time Monitoring

- Live server statistics, including TPS, memory usage, and CPU usage.
- Historical performance graphs and analytics.
- Online player tracking with detailed player profiles.
- Live console log streaming with color-coded output.
- Setup-safe telemetry endpoints for panel integrations.
- Notifications, graph snapshots, and performance views for server health checks.

### Guardian

- Guardian activity review with bounded table heights and sticky headers.
- Client-side paging for Timeline, Blocks, and Containers.
- Full-width responsive investigation tool grid.
- Subtle row motion when paging or switching Guardian tabs.
- Selection, Status, Insights, Cases, Notes, Rollback, Regions, Rules, Retention, Purge, and CoreProtect tools.

### Player and Staff Management

- View all players with session history and playtime tracking.
- Kick, ban, freeze, or teleport players from the web panel.
- Add and manage admin notes for players.
- View and edit player inventories and ender chests.
- Manage the server whitelist.
- Use Staff tools for operator workflows.

### Server Configuration

- Modify common server settings such as MOTD, view distance, and simulation distance.
- Configure game rules across worlds.
- Upload and manage the server icon.
- Manage supported server-side settings directly from the dashboard.

### Files and Uploads

- Browse server files and directories directly in the browser.
- Open files and folders from the whole row bubble for faster navigation.
- Edit configuration files from the web interface.
- Upload, rename, download, and delete files while staying in context.
- Keep guarded path handling for protected server runtime files.
- Upload and manage datapacks.

### Plugin Maintenance

- Keep Paper plugin maintenance local to Dash.
- Manage installed plugin state through Plugin Manager.
- Browse plugin options through Plugin Browser.
- Use Modrinth search where supported.
- Surface plugin update hints and startup inventory scan context.
- Diagnose local server issues with Dash Doctor.

### Backup System

- Create manual backups instantly.
- Use Advanced Backups for richer backup workflows.
- Schedule automatic backups.
- Download backup archives.
- Configure backup retention with a maximum backup limit.

### Console and Moderation Tools

- Execute console commands remotely.
- Teleport players to coordinates or to other players.
- Broadcast messages as the server or as an admin.
- Use the player freeze system for moderation workflows.
- Register web admins through time-limited in-game registration codes.

### SSO and Approval Workflow

- NeoDash bridge SSO bootstrap flow.
- Waiting room for identities pending admin approval.
- Review pending bridge users directly from the Users page.
- Assign roles during bridge approval.
- Bridge-aware navigation and session handoff.
- NeoDash restart handoff for configured start paths and startup-log visibility.

### GitHub-Backed Updates

- GitHub-backed update checks and release visibility for Dash.
- Rate-limit-aware update scanning.
- Manual retry support for update scans.
- Staged downloads that can be applied cleanly on restart.
- Clearer update workflow for server operators.

### Responsive Web UI

- Reworked global scrolling behavior.
- Mobile-first handling for Players, Tasks, Audit, and Guardian pages.
- Improved overflow and clipping behavior for action menus and card actions.
- Better mobile form layouts in Settings pages.
- Smooth dashboard motion system with reduced-motion support.

## Installation

1. Download the latest `Dash.jar` from the [releases page](../../releases).
2. Place the JAR file in your server's `plugins` folder.
3. Restart your Minecraft server.
4. Configure the web port in `plugins/Dash/config.yml` if needed. The default port is `8080`.
5. Restart the server again to apply configuration changes.

## Initial Setup

1. Join your server as an operator.
2. Run `/dash register` in-game to generate a registration code.
3. Open the dashboard in your browser:

   ```text
   http://localhost:8080
   ```

   Replace `8080` with your configured port if you changed it.

4. Enter the registration code and create your admin account.
5. Log in and start managing your server through Dash.

> For production use, run Dash behind HTTPS through a reverse proxy or a secure hosting panel whenever the dashboard is exposed outside your local machine.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/dash register` | Generates a web registration code that expires after 5 minutes. | `dash.register` |

## Permissions

| Permission | Description | Default |
| --- | --- | --- |
| `dash.register` | Allows a player to generate web registration codes. | `op` |

## Configuration

Edit the Dash configuration file:

```text
plugins/Dash/config.yml
```

Example configuration:

```yaml
# Web server port for the admin dashboard
port: 8080

# Database settings for player data tracking
database:
  type: sqlite
  file: dash.db

# Backup settings
backups:
  directory: backups
  max-backups: 10
```

### Configuration Options

| Option | Description | Default |
| --- | --- | --- |
| `port` | Web server port used by the dashboard. | `8080` |
| `database.type` | Database backend for player data tracking. Currently supports SQLite. | `sqlite` |
| `database.file` | SQLite database file name. | `dash.db` |
| `backups.directory` | Directory used for backup archives. | `backups` |
| `backups.max-backups` | Maximum number of backups to retain. | `10` |

## Supported Versions

| Requirement | Supported |
| --- | --- |
| Minecraft | `1.21`, `1.21.1`, `1.21.2`, `1.21.3`, `1.21.4+` |
| Server software | Paper, Spigot, Bukkit, and compatible forks |
| Java | `21` or higher |

## Security

Dash includes several built-in security measures for safer server administration:

- Registration codes expire after 5 minutes.
- Session-based authentication with secure cookie handling.
- Explicit logout flow and session hardening updates.
- Admin credentials are hashed before storage.
- File uploads are validated.
- File paths are sanitized to reduce traversal risks.
- File-manager actions use guarded path handling for protected server runtime files.
- Administrative actions are logged with IP tracking.
- Bridge approvals keep SSO identities gated behind admin review.

## Recommended Production Setup

When exposing the dashboard publicly, use a secure deployment setup:

- Put Dash behind HTTPS.
- Restrict the dashboard port with a firewall where possible.
- Use strong admin credentials.
- Only approve bridge users you trust.
- Keep Dash updated through the latest GitHub release.
- Regularly download or rotate important backups.
- Review Guardian activity, cases, rules, retention, purge, and CoreProtect tools regularly.

## Previous Release

### Dash 3.1

Dash 3.1 introduced the first premium motion pass, local maintenance pages, the plugin browser, notifications, staff workflow, graph snapshots, mobile navigation fixes, and NeoDash restart handoff.

## Author

Developed by **Frxme**.

## License

This project is licensed under the **BSD 3-Clause License**. See [LICENSE](LICENSE) for details.

---

<div align="center">

## Partner

<a href="https://emeraldhost.de/frxme">
  <img src="https://cdn.emeraldhost.de/branding/icon/icon.png" width="80" alt="Emerald Host Logo">
</a>

### Powered by EmeraldHost

*DDoS-Protection, NVMe Performance und 99.9% Uptime.*  
*Der Host meines Vertrauens für alle Development-Server.*

<a href="https://emeraldhost.de/frxme">
  <img src="https://img.shields.io/badge/Code-Frxme10-10b981?style=for-the-badge&logo=gift&logoColor=white&labelColor=0f172a" alt="Use code Frxme10 for 10% off">
</a>

</div>

---

*For issues, feature requests, or contributions, please visit the [GitHub repository](../../issues).*