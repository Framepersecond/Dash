# Dash Release Notes

## 4.5.1 - 2026-09-17

Dash 4.5.1 is the Minecraft 26.3 companion release.

- Verified against Minecraft 26.3 servers; the plugin stays a single artifact for every supported server version.
- Fixed same-origin form actions such as Restart and Stop being rejected as cross-site requests over plain HTTP.
- Added `base-path` so the panel can be served below a sub-path of a reverse proxy.
- Hardened HTML and JavaScript escaping in the file manager and page renderers.
- Sessions of a deleted user are now dropped immediately instead of surviving until their TTL.

## 4.5 - 2026-08-31

Dash 4.5 replaces Dash Doctor with an optional, consent-first Dash AI workspace and brings Tickets, Notifications, Graphs, and Guardian into the compact Intelligence interface.

### Dash AI

- Fixed Test Key blocking the single HTTP worker and freezing the entire dashboard; AI configuration now uses a bounded background request and the web server remains responsive.
- Added backend-only Google Gemini Interactions support with Flash and Pro model choices; provider keys never enter browser responses.
- Added environment-key overrides and an AES-256-GCM local vault with masked fingerprints, connection testing, rotation, and removal.
- Added versioned owner and operator consent, 30-day local conversations, sanitized context selection, SSE responses, cancellation, bounded retries, and user-isolated history.
- Limited automatic tools to read operations. Every mutation is an expiring, hashed proposal with permission checks, CSRF enforcement, reason capture, typed confirmation for high-risk work, restore points where applicable, idempotency, and audit metadata without prompt contents.
- Redirected `/doctor` to `/ai`; deterministic crash and log diagnostics remain available in Maintenance without Google.

### Unified workspaces

- Rebuilt Tickets around Inbox, My Tickets, New Report, and Staff Notes with filtering, validation, pagination, detail controls, and permission-aware updates.
- Organized Notifications into Overview, Destinations, Events, and Delivery Health while retaining real Discord delivery and testing.
- Added responsive Graph ranges, pause/live control, tooltips, empty states, and JSON/CSV export.
- Shortened Guardian into Investigate, Cases, Protection, Recovery, and Retention with paged activity and guarded destructive actions.
- Added compact radii, full-width tabs, stable loading/error states, smoother transitions, and reduced-motion behavior across the release.
- Expanded the shared language layer across current workspaces: English remains the complete authored default and Spanish now covers Dash AI, Intelligence, Guardian, Tickets, Notifications, Graphs, Maintenance, forms, states, and errors.

## 4.3 - 2026-07-13

Dash 4.3 is the Intelligence Center release. It adds 25 persistent, permission-aware operator features and completes the animation, file, Guardian, Paper interface and action-safety work started in 4.0.

### Intelligence Center

- Added Shadow Boot Lab, Root Cause Explorer, Performance Regression Radar, Plugin Resource Attribution, Guided Safe Mode and Dependency and Impact Map.
- Added State Time Machine, Backup Content Explorer, Adaptive Maintenance Window and Action Guardrails.
- Added Player Journey Replay, Support and Appeals Inbox, Cross-Server Player Identity and Player Experience Score.
- Added Just-in-Time Staff Access, Dual-Control Actions and Retention Center.
- Added Supply Chain Center, Config Schema Assistant and Universal Search.
- Added World Health Center, Storage Intelligence and Service Level Dashboard.
- Added Operator War Room and a standalone Public Status Page.

### Recovery and action safety

- Made Shadow Boot input copies independent from live configuration.
- Made state restore staged and integrity verified, with automatic safety snapshots, removal of later-added tracked files and rollback on failure.
- Added generic guardrail enforcement to all Intelligence mutations, including an AJAX reason challenge and a safe full-page fallback.
- Tightened configuration, retention, support, war-room, status, JIT-access and performance input validation.

### Motion and workflow completion

- Reworked SPA swaps so the current page remains usable until fetched content is ready and incoming sections never start from a blank frame.
- Shortened and softened card stagger timing while retaining reduced-motion behavior and meaningful loading feedback.
- Replaced the browser-side Tailwind compiler with a minified, immutable local stylesheet for faster and more deterministic first paint.
- Retained scroll during in-place uploads, whole-bubble file navigation, paged Guardian activity and full-width Guardian tools.
- Verified every Intelligence action, select option, support type/status, incident severity/kind, public status state and persistence path across the shared Dash variants.

### Release metadata

- Updated Maven, `plugin.yml`, downloader and plugin-browser identities to `4.3`.
- Added publication-ready GitHub and Modrinth copy for the full release history since 3.1.

## 4.2 - 2026-07-12

Dash 4.2 introduces a durable Operations Center for Paper and Bukkit-family servers. Every control is backed by persistent state or an existing production service; Deployment Rings are intentionally not part of this release.

### Plan and respond

- Added Maintenance Planner, Change Preview, verified-backup preflight and Maintenance Calendar.
- Added Incident Mode with ownership, severity, resolution capture and generated Post-Incident Reports.
- Added Shift Handover, Contextual Quick Actions and acknowledgeable Smart Alert Bundles.

### Recover and verify

- Added non-destructive Restore Drills with archive path, duplicate-entry, entry-count, size, full-stream and CRC validation.
- Added Configuration Drift baselines and Capacity Forecasting with retained samples.
- Added Compatibility Center checks for loader descriptors, unreadable JARs and duplicate artifacts.

### Security and daily workflow

- Added Permission Simulator, security evidence JSON export and fingerprint-only secret visibility.
- Added coordinated live bridge-secret rotation support for NeoDash 2.2.
- Added real Automation Recipes for backups, restart, save and maintenance notices.
- Added Player 360, Mobile Operations Mode and action-aware command palette search.
- Added SQLite to the Paper artifact explicitly and a cross-variant end-to-end operations regression suite.

## 4.1 - 2026-07-12

Dash 4.1 is the completion release for the 4.x dashboard refresh. It keeps the smooth 4.0 interface while turning the exposed beta areas into clear, end-to-end tools.

### Completed beta workflows

- Replaced unsupported email, mobile and cloud placeholders with a focused Delivery Center.
- Added live Discord destination counts and test delivery through the production webhook transport.
- Made Staff tickets and notes use bounded input, validated states, atomic file replacement and honest failure messages.
- Added Spark availability reporting while keeping the existing Paper profiler launch flow.
- Hardened Dash Doctor review and delete operations against symlink-backed crash files.

### Reliability fixes

- Fixed Paper dashboard HTML responses to always declare `text/html; charset=utf-8`, preventing browsers with `nosniff` from showing the interface as source code.
- Removed unload-triggered logout, so refreshes and direct navigation keep the authenticated session.
- Rebuilt backups around verified partial archives, excluded the backup directory from its own input, retained plugin JARs, and made create/delete results report the real completed outcome.
- Verified every visible backup interval, Staff priority and status, Dash Doctor action, profiler action, upload path and beta gate in a real Paper runtime.

### Product introduction and motion

- Added an accessible, once-per-version introduction explaining the 4.1 motion, beta and security changes.
- Kept the smoother route and content-swap layer, loading states, whole-row file interaction, in-place uploads and reduced-motion behavior introduced in 4.0.

### Security and release metadata

- Preserved CSRF enforcement, RBAC, canonical path checks, protected-file rules, bounded request bodies, SSRF defenses, webhook validation and digest-verified updates.
- Updated Maven metadata, `plugin.yml`, downloader identity and release documentation to `4.1`.

## 4.0 - 2026-07-06

Dash 4.0 is the major Paper/Bukkit-family release after 3.1. It turns the dashboard into a smoother, more complete operations surface while keeping the familiar single-server workflow.

### Motion and interface polish

- Rebuilt the shared dashboard animation layer around smoother route changes, content swaps, card entrance timing, row reveals, hover lift, press feedback, status flips and value flashes.
- Removed the bumpy pre-exit navigation feel that made page switches feel delayed or disconnected from user input.
- Kept reduced-motion behavior available for operators who prefer less animation.
- Tuned interaction feedback toward transform/opacity patterns for better browser performance.

### Guardian

- Shortened the Guardian activity area with a bounded table height and sticky headers.
- Added client-side paging for Timeline, Blocks and Containers so Guardian no longer stretches into an extremely long page.
- Moved Guardian investigation tools out of the right-side tower into a full-width responsive tool grid.
- Kept Selection, Status, Insights, Cases, Notes, Rollback, Regions, Rules, Retention, Purge and CoreProtect tools available with less scrolling.
- Added subtle row motion when paging or switching Guardian activity tabs.

### Files and uploads

- File and folder rows now behave like clickable bubbles, so opening a folder no longer requires clicking only the filename.
- File-manager actions keep the existing guarded path handling for protected lock and pid files.
- Upload, edit, rename, download and delete flows remain inside the browser experience without forcing unnecessary navigation.

### GitHub and update workflows

- Expanded GitHub-backed update checks and release visibility for Dash.
- Preserved staged update behavior so downloads can be applied cleanly on restart.
- Kept rate-limit aware update scanning and manual retry behavior.

### Maintenance and staff tools

- Continued the local maintenance split: Paper plugin work belongs in Dash, while fleet orchestration belongs in NeoDash.
- Kept Advanced Backups, Plugin Manager, Plugin Browser, Dash Doctor, Staff, Notifications and Graphs as plugin-local tools.
- Improved the release narrative around Modrinth search, startup inventory scans, plugin update hints and local server diagnostics.

### NeoDash bridge

- Restart actions from NeoDash SSO sessions delegate back to NeoDash's configured start path.
- Restart now routes into the NeoDash startup-log flow so operators can watch the server come back online.
- Bridge sessions keep the master dashboard URL and restart callback for future lifecycle actions.

### Release metadata

- Maven project version updated to `4.0`.
- `plugin.yml` version updated to `4.0`.

## 3.1 - Previous Release

Dash 3.1 introduced the first premium motion pass, local maintenance pages, the plugin browser, notifications, staff workflow, graph snapshots, mobile navigation fixes and NeoDash restart handoff.
# 4.4

- Minecraft 1.21.x and 26.2 compatibility from one Paper-family plugin.
- Operations removed in favor of the complete Intelligence Center.
- Legacy Operations routes redirect safely to Intelligence.
