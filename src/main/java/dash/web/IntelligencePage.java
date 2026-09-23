package dash.web;

import dash.data.IntelligenceManager;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compact interface for the advanced intelligence workflows shared by all Dash platforms. */
public final class IntelligencePage {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Set<String> TABS = Set.of("lab", "change", "players", "policy", "supply",
            "reliability", "response");

    private IntelligencePage() {
    }

    public static String render(IntelligenceManager manager, String message, String rawQuery, boolean canWrite,
            boolean canManageUsers, String actor, long serverId, List<ServerOption> servers,
            RuntimeMetrics metrics, List<IntelligenceManager.SearchHit> searchOverride,
            List<IntelligenceManager.PlayerIdentity> identityOverride) {
        return render(manager, manager, message, rawQuery, canWrite, canManageUsers, actor, serverId, servers,
                metrics, searchOverride, identityOverride);
    }

    public static String render(IntelligenceManager manager, IntelligenceManager accessManager, String message,
            String rawQuery, boolean canWrite, boolean canManageUsers, String actor, long serverId,
            List<ServerOption> servers, RuntimeMetrics metrics,
            List<IntelligenceManager.SearchHit> searchOverride,
            List<IntelligenceManager.PlayerIdentity> identityOverride) {
        IntelligenceManager access = accessManager == null ? manager : accessManager;
        Map<String, String> query = parseQuery(rawQuery);
        String requestedTab = query.get("tab");
        String tab = requestedTab != null && TABS.contains(requestedTab) ? requestedTab : "lab";
        RuntimeMetrics live = metrics == null ? RuntimeMetrics.offline() : metrics;
        List<ServerOption> availableServers = servers == null ? List.of() : List.copyOf(servers);
        IntelligenceManager.ServiceLevel service = manager.serviceLevel();
        long openCases = manager.supportCases("open", "", 300).size();
        long pendingApprovals = manager.approvals(300).stream().filter(row -> "pending".equals(row.status())).count();

        String banner = message == null || message.isBlank() ? ""
                : "<div class='intel-banner' role='status'><span class='material-symbols-outlined'>info</span><span>"
                        + esc(message) + "</span></div>";
        String content = HtmlTemplate.statsHeader()
                + styles()
                + "<main class='intel-main flex-1 min-w-0 p-4 sm:p-6'><div class='max-w-7xl mx-auto space-y-4'>"
                + header(serverId, availableServers, tab, live)
                + banner
                + metrics(live, service, openCases, pendingApprovals)
                + "<section class='intel-workspace' data-intel-tab='" + tab + "'>"
                + switch (tab) {
                    case "change" -> changePanel(manager, query, canWrite, serverId, live);
                    case "players" -> playersPanel(manager, query, canWrite, serverId, identityOverride);
                    case "policy" -> policyPanel(manager, access, query, canWrite, canManageUsers, serverId);
                    case "supply" -> supplyPanel(manager, query, serverId, searchOverride);
                    case "reliability" -> reliabilityPanel(manager, canWrite, serverId, live);
                    case "response" -> responsePanel(manager, canWrite, serverId);
                    default -> labPanel(manager, query, canWrite, serverId);
                }
                + "</section></div></main>"
                + script()
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Intelligence Center", "/intelligence", content);
    }

    public static String renderPublicStatus(IntelligenceManager.PublicStatus status, String serverName,
            long selectedServer, List<ServerOption> servers) {
        String displayName = serverName == null || serverName.isBlank() ? "Dash" : serverName.trim();
        String initial = displayName.substring(0, 1).toUpperCase(Locale.ROOT);
        IntelligenceManager.PublicStatus safe = status == null
                ? new IntelligenceManager.PublicStatus("operational", List.of(), List.of(), System.currentTimeMillis())
                : status;
        StringBuilder components = new StringBuilder();
        for (IntelligenceManager.StatusComponent component : safe.components()) {
            components.append("<li><span class='status-dot status-").append(cssToken(component.status()))
                    .append("'></span><span><strong>").append(esc(component.component())).append("</strong><small>")
                    .append(esc(component.message())).append("</small></span><b>")
                    .append(esc(human(component.status()))).append("</b></li>");
        }
        if (components.isEmpty()) components.append("<li><span class='status-dot status-operational'></span><span><strong>Service</strong><small>No component notices.</small></span><b>Operational</b></li>");

        StringBuilder incidents = new StringBuilder();
        for (IntelligenceManager.PublicIncident incident : safe.incidents()) {
            incidents.append("<article><div><span class='incident-severity severity-")
                    .append(cssToken(incident.severity())).append("'>").append(esc(human(incident.severity())))
                    .append("</span><time>").append(time(incident.startedAt())).append("</time></div><h2>")
                    .append(esc(incident.title())).append("</h2><p>").append(esc(incident.message()))
                    .append("</p><small>").append("closed".equals(incident.status()) ? "Resolved " + time(incident.resolvedAt()) : "Monitoring in progress")
                    .append("</small></article>");
        }
        if (incidents.isEmpty()) incidents.append("<div class='empty'>No published incidents.</div>");

        String selector = publicServerSelector(selectedServer, servers);
        String overall = cssToken(safe.overall());
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='color-scheme' content='dark'><title>" + esc(displayName) + " status</title>"
                + publicStyles() + "</head><body><main><header><div class='brand'><span>" + esc(initial)
                + "</span><strong>" + esc(displayName) + " Status</strong></div>"
                + selector + "</header><section class='hero status-" + overall + "'><div class='pulse'><i></i></div><div><p>"
                + esc(displayName) + "</p><h1>" + esc(human(safe.overall())) + "</h1><small>Updated "
                + time(safe.generatedAt()) + "</small></div></section><section class='layout'><div><h2 class='section-title'>Components</h2><ul class='components'>"
                + components + "</ul></div><div><h2 class='section-title'>Incident history</h2><div class='incidents'>"
                + incidents + "</div></div></section><footer>Live operational status</footer></main></body></html>";
    }

    public static String renderUnavailable(String incident) {
        String reference = clean(incident, 32, "unknown");
        String content = HtmlTemplate.statsHeader()
                + styles()
                + "<main class='intel-main flex-1 min-w-0 p-4 sm:p-6'><div class='max-w-7xl mx-auto space-y-4'>"
                + "<header class='intel-hero'><div class='intel-hero-copy'><div class='intel-kicker'>"
                + "<span class='material-symbols-outlined'>neurology</span>Intelligence Center</div>"
                + "<h1>Intelligence is recovering</h1><p>The dashboard caught an unexpected data condition instead of dropping the connection.</p></div></header>"
                + "<section class='intel-workspace'><div class='intel-banner' role='alert'>"
                + "<span class='material-symbols-outlined'>warning</span><span>Reload this page once. If it persists, search the server log for incident <b>"
                + esc(reference) + "</b>.</span></div></section></div></main>"
                + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Intelligence Center", "/intelligence", content);
    }

    private static String header(long serverId, List<ServerOption> servers, String tab, RuntimeMetrics metrics) {
        String selector = serverSelector(serverId, servers, tab);
        return "<header class='intel-hero'><div class='intel-hero-copy'><div class='intel-kicker'><span class='material-symbols-outlined'>neurology</span>Intelligence Center</div>"
                + "<h1>Intelligence</h1><p>Investigation, player care, policy and service reliability.</p></div>"
                + "<div class='intel-hero-actions'>" + selector
                + "<a class='intel-status-link' href='/status" + (serverId > 0 ? "?id=" + serverId : "")
                + "' target='_blank' rel='noopener'><span class='material-symbols-outlined'>public</span>Public status</a>"
                + "<span class='intel-live " + (metrics.online() ? "is-online" : "is-offline") + "'><i></i>"
                + (metrics.online() ? "Live" : "Offline") + "</span></div></header>";
    }

    private static String metrics(RuntimeMetrics metrics, IntelligenceManager.ServiceLevel service,
            long openCases, long pendingApprovals) {
        String serviceValue = service.samples() == 0 ? "Learning" : format(service.availabilityPercent(), 2) + "%";
        return "<section class='intel-metrics' aria-label='Current intelligence metrics'>"
                + metric("TPS", format(metrics.tps(), 1), "speed", metrics.tps() >= 18 ? "good" : "watch")
                + metric("Players", Integer.toString(metrics.players()), "groups", "neutral")
                + metric("30-day availability", serviceValue, "monitoring", "meeting".equals(service.status()) ? "good" : "watch")
                + metric("Open support", Long.toString(openCases), "support_agent", openCases == 0 ? "good" : "watch")
                + metric("Pending approvals", Long.toString(pendingApprovals), "approval", pendingApprovals == 0 ? "good" : "watch")
                + "</section>";
    }

    private static String metric(String label, String value, String icon, String tone) {
        return "<div class='intel-metric tone-" + tone + "'><span class='material-symbols-outlined'>" + icon
                + "</span><div><small>" + esc(label) + "</small><strong>" + esc(value)
                + "</strong></div><details class='intel-help'><summary aria-label='Explain " + esc(label)
                + "'>?</summary><p>" + esc(metricHelp(label)) + "</p></details></div>";
    }

    private static String metricHelp(String label) {
        return switch (label) {
            case "TPS" -> "Current server ticks per second. Values near 20 indicate healthy game-loop performance.";
            case "Players" -> "Players currently connected to this server.";
            case "30-day availability" -> "Observed service availability calculated from Dash health samples during the last 30 days.";
            case "Open support" -> "Support cases that still need an operator response or resolution.";
            case "Pending approvals" -> "Protected changes waiting for an authorized operator to approve or reject them.";
            default -> "Current value collected by Dash for this workspace.";
        };
    }

    private static String labPanel(IntelligenceManager manager, Map<String, String> query, boolean canWrite,
            long serverId) {
        IntelligenceManager.RootCauseReport rootCause = manager.rootCauseReport();
        List<IntelligenceManager.ShadowLab> labs = manager.shadowLabs(8);
        List<String> artifacts = manager.artifactNames();
        List<IntelligenceManager.SafeModeItem> safeMode = manager.safeModeItems(20);
        List<String> backups = manager.backupCandidates();
        String selectedBackup = clean(query.get("backup"), 180, "");
        if (!backups.contains(selectedBackup)) selectedBackup = "";
        IntelligenceManager.BackupView backup = selectedBackup.isBlank() ? null
                : manager.browseBackup(selectedBackup, query.get("backup_q"), 120);

        return "<div class='intel-grid'>"
                + card("Shadow Boot Lab", "experiment", "Boot this server in an isolated loopback workspace before a risky rollout.",
                        (canWrite ? actionButton("intel_shadow_start", "lab", serverId, "science", "Run isolated boot", "primary") : readOnly())
                                + shadowRows(labs))
                + card("Root Cause Explorer", "troubleshoot", "Correlates current failure signatures with recent configuration and artifact changes.",
                        "<div class='intel-verdict verdict-" + cssToken(rootCause.severity()) + "'><strong>"
                                + esc(rootCause.summary()) + "</strong><span>" + esc(human(rootCause.severity())) + "</span></div>"
                                + evidence(rootCause.evidence(), "Evidence") + evidence(rootCause.recommendations(), "Recommended next steps"))
                + card("Guided Safe Mode", "shield_lock", "Quarantine one plugin or mod, test safely, then restore it without path ambiguity.",
                        safeModeForm(artifacts, canWrite, serverId) + quarantineRows(safeMode, canWrite, serverId))
                + cardWide("Backup Content Explorer", "folder_zip", "Inspect archive contents and restore only the selected verified file.",
                        backupExplorer(backups, selectedBackup, backup, query.get("backup_q"), canWrite, serverId))
                + "</div>";
    }

    private static String changePanel(IntelligenceManager manager, Map<String, String> query, boolean canWrite,
            long serverId, RuntimeMetrics metrics) {
        List<IntelligenceManager.StateSnapshot> snapshots = manager.stateSnapshots(20);
        String selected = clean(query.get("snapshot"), 80, snapshots.isEmpty() ? "" : snapshots.get(0).id());
        IntelligenceManager.StateDiff diff = selected.isBlank()
                ? new IntelligenceManager.StateDiff(false, 0, 0, 0, List.of("Capture a snapshot to begin."))
                : manager.diffState(selected);
        IntelligenceManager.PerformanceRegression regression = manager.performanceRegression();
        IntelligenceManager.DependencyGraph graph = manager.dependencyGraph();
        IntelligenceManager.MaintenanceWindow window = manager.maintenanceWindow();
        return "<div class='intel-grid'>"
                + cardWide("State Time Machine", "history", "Capture, compare and transactionally restore exact configuration and runtime state with automatic rollback.",
                        stateMachine(snapshots, selected, diff, canWrite, serverId))
                + card("Performance Regression Radar", "radar", "Compares live performance with a named pre-change baseline.",
                        performanceRadar(regression, metrics, canWrite, serverId))
                + card("Dependency & Impact Map", "account_tree", "Reads loader metadata to show required, optional and missing dependencies.",
                        dependencyMap(graph))
                + card("Adaptive Maintenance Window", "event_available", "Learns the quietest hour from sixty days of actual player joins.",
                        "<div class='intel-big-value'><strong>" + esc(human(window.day())) + " " + pad(window.hour())
                                + ":00</strong><span>" + percent(window.confidence()) + " confidence</span></div><p class='intel-note'>"
                                + esc(window.message()) + "</p><div class='intel-progress'><i style='width:"
                                + (int) Math.round(window.confidence() * 100) + "%'></i></div>" )
                + "</div>";
    }

    private static String playersPanel(IntelligenceManager manager, Map<String, String> query, boolean canWrite,
            long serverId, List<IntelligenceManager.PlayerIdentity> identityOverride) {
        String player = clean(query.get("player"), 64, "");
        List<IntelligenceManager.PlayerIdentity> identities = identityOverride != null ? identityOverride
                : player.isBlank() ? List.of() : manager.searchPlayerIdentities(player, 50);
        IntelligenceManager.PlayerJourney journey = player.isBlank() ? null : manager.playerJourney(player, 120);
        IntelligenceManager.ExperienceScore experience = player.isBlank() ? null : manager.playerExperience(player);
        List<IntelligenceManager.SupportCase> support = manager.supportCases(query.get("support_status"), "", 80);
        return "<div class='intel-grid'>"
                + cardWide("Cross-Server Player Identity", "badge", "Find a player by name or UUID and see one stable identity across the fleet.",
                        playerSearch(player, identities, serverId))
                + card("Player Journey Replay", "route", "Reconstructs sessions, staff notes, Guardian activity and support touchpoints.",
                        journey == null ? empty("Search for a player to build their journey.") : journey(journey))
                + card("Player Experience Score", "sentiment_satisfied", "Turns abrupt sessions, short visits and related errors into a clear service signal.",
                        experience == null ? empty("Search for a player to calculate experience quality.") : experience(experience))
                + cardWide("Support & Appeals Inbox", "support_agent", "Own support, appeals and player-visible replies through a complete workflow.",
                        supportInbox(support, canWrite, serverId))
                + "</div>";
    }

    private static String policyPanel(IntelligenceManager manager, IntelligenceManager accessManager,
            Map<String, String> query, boolean canWrite, boolean canManageUsers, long serverId) {
        List<IntelligenceManager.Guardrail> guardrails = manager.guardrails();
        List<IntelligenceManager.TemporaryGrant> grants = accessManager.temporaryGrants(false);
        List<IntelligenceManager.Approval> approvals = manager.approvals(80);
        IntelligenceManager.RetentionPolicy retention = manager.retentionPolicy();
        IntelligenceManager.RetentionPreview preview = manager.retentionPreview();
        List<String> configs = manager.configCandidates();
        String selectedConfig = clean(query.get("config"), 500, configs.isEmpty() ? "" : configs.get(0));
        IntelligenceManager.ConfigDocument config = selectedConfig.isBlank() ? null : manager.inspectConfig(selectedConfig);
        return "<div class='intel-grid'>"
                + cardWide("Action Guardrails", "policy", "Block unsafe mutations by player count, backup age, quiet hours, reason or second approval.",
                        guardrailEditor(guardrails, canWrite, serverId))
                + card("Just-in-Time Staff Access", "timer", "Grant a narrow permission for a bounded period and revoke it immediately.",
                        accessGrants(grants, canWrite && canManageUsers, serverId))
                + card("Dual-Control Actions", "approval", "A second operator approves the exact action payload; approvals are single-use.",
                        approvals(approvals, canWrite, serverId))
                + card("Retention Center", "auto_delete", "Preview and enforce bounded log, crash and backup retention while preserving recovery generations.",
                        retention(retention, preview, canWrite, serverId))
                + cardWide("Config Schema Assistant", "data_object", "Inspect inferred scalar types and update supported formats behind an automatic safety snapshot.",
                        configAssistant(configs, selectedConfig, config, canWrite, serverId))
                + "</div>";
    }

    private static String supplyPanel(IntelligenceManager manager, Map<String, String> query, long serverId,
            List<IntelligenceManager.SearchHit> searchOverride) {
        List<IntelligenceManager.ResourceAttribution> attribution = manager.resourceAttribution();
        List<IntelligenceManager.SupplyArtifact> supply = manager.supplyChain();
        String search = clean(query.get("q"), 160, "");
        List<IntelligenceManager.SearchHit> hits = searchOverride != null ? searchOverride
                : search.isBlank() ? List.of() : manager.universalSearch(search, 120);
        return "<div class='intel-grid'>"
                + cardWide("Fleet Universal Search", "search", "Search players, support, logs, configuration and runtime artifacts from one query.",
                        universalSearch(search, hits, serverId))
                + card("Plugin Resource Attribution", "memory", "Ranks likely resource impact using artifact inventory and recent warning evidence.",
                        attribution(attribution))
                + card("Supply Chain Center", "deployed_code", "Fingerprints every JAR, verifies readable content and flags native or executable payloads.",
                        supplyChain(supply))
                + "</div>";
    }

    private static String reliabilityPanel(IntelligenceManager manager, boolean canWrite, long serverId,
            RuntimeMetrics metrics) {
        IntelligenceManager.WorldHealth world = manager.worldHealth();
        IntelligenceManager.StorageReport storage = manager.storageIntelligence();
        IntelligenceManager.ServiceLevel service = manager.serviceLevel();
        return "<div class='intel-grid'>"
                + card("World Health Center", "public", "Validates region headers and chunk locations without loading live world chunks.", worldHealth(world))
                + cardWide("Storage Intelligence", "hard_drive", "Classifies managed storage, shows volume headroom and previews reclaimable bytes.", storage(storage))
                + card("Service Level Dashboard", "monitoring", "Tracks availability, TPS, MSPT and backup freshness against a thirty-day objective.",
                        serviceLevel(service, metrics, canWrite, serverId))
                + "</div>";
    }

    private static String responsePanel(IntelligenceManager manager, boolean canWrite, long serverId) {
        List<IntelligenceManager.WarRoom> rooms = manager.warRooms(40);
        IntelligenceManager.PublicStatus status = manager.publicStatus();
        return "<div class='intel-grid'>"
                + cardWide("Operator War Room", "emergency_home", "Coordinate ownership, decisions, actions and a publishable incident timeline.",
                        warRooms(rooms, canWrite, serverId))
                + cardWide("Public Status Page", "public", "Publish sanitized component health and selected incident updates without exposing the admin panel.",
                        statusEditor(status, canWrite, serverId))
                + "</div>";
    }

    private static String shadowRows(List<IntelligenceManager.ShadowLab> rows) {
        if (rows.isEmpty()) return empty("No isolated startup has been run yet.");
        StringBuilder out = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.ShadowLab row : rows) {
            out.append(row("experiment", row.summary(), human(row.status()), time(row.createdAt()), row.status()));
        }
        return out.append("</div>").toString();
    }

    private static String evidence(List<String> rows, String title) {
        StringBuilder out = new StringBuilder("<div class='intel-evidence'><b>").append(esc(title)).append("</b><ul>");
        if (rows == null || rows.isEmpty()) out.append("<li>No evidence in the current window.</li>");
        else for (String item : rows) out.append("<li>").append(esc(item)).append("</li>");
        return out.append("</ul></div>").toString();
    }

    private static String safeModeForm(List<String> artifacts, boolean canWrite,
            long serverId) {
        if (!canWrite) return readOnly();
        StringBuilder options = new StringBuilder();
        for (String artifact : artifacts) {
            options.append(option(artifact, artifact, false));
        }
        if (options.isEmpty()) options.append("<option value=''>No active artifacts</option>");
        return formStart("intel_safe_quarantine", "lab", serverId, "intel-inline-form")
                + "<label><span>Plugin or mod</span><select name='artifact' required>" + options + "</select></label>"
                + submit("shield_lock", "Quarantine for next restart", "danger") + "</form>";
    }

    private static String quarantineRows(List<IntelligenceManager.SafeModeItem> rows, boolean canWrite, long serverId) {
        List<IntelligenceManager.SafeModeItem> active = rows.stream().filter(row -> row.restoredAt() == 0).toList();
        if (active.isEmpty()) return empty("No artifact is currently quarantined.");
        StringBuilder out = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.SafeModeItem item : active) {
            String action = canWrite ? formStart("intel_safe_restore", "lab", serverId, "intel-row-action")
                    + hidden("quarantine_id", item.id()) + submit("restore", "Restore", "quiet") + "</form>" : "";
            out.append("<div class='intel-row'><span class='material-symbols-outlined'>inventory_2</span><div><strong>")
                    .append(esc(item.artifact())).append("</strong><small>Quarantined ").append(time(item.createdAt()))
                    .append(" by ").append(esc(item.actor())).append("</small></div>").append(action).append("</div>");
        }
        return out.append("</div>").toString();
    }

    private static String backupExplorer(List<String> backups, String selected, IntelligenceManager.BackupView view,
            String search, boolean canWrite, long serverId) {
        if (backups.isEmpty()) return empty("No managed ZIP backup is available.");
        StringBuilder options = new StringBuilder();
        for (String backup : backups) options.append(option(backup, backup, backup.equals(selected)));
        String out = "<form method='get' action='/intelligence' class='intel-filter'>" + hidden("tab", "lab")
                + serverHidden(serverId) + "<label><span>Backup</span><select name='backup'>" + options
                + "</select></label><label><span>Find entry</span><input name='backup_q' value='" + esc(search)
                + "' maxlength='160' placeholder='world/datapacks'></label>" + filterButton() + "</form>";
        if (view == null) return out;
        StringBuilder rows = new StringBuilder("<div class='intel-table'><div class='intel-table-head'><span>Archive path</span><span>Live state</span><span>Size</span><span></span></div>");
        for (IntelligenceManager.BackupEntry entry : view.entries()) {
            String action = canWrite ? formStart("intel_backup_restore", "lab", serverId, "intel-row-action")
                    + hidden("backup", selected) + hidden("entry", entry.path())
                    + submit("restore_page", "Restore", "quiet") + "</form>" : "";
            rows.append("<div class='intel-table-row'><span class='intel-path'>").append(esc(entry.path()))
                    .append("</span><span>").append(badge(entry.liveState())).append("</span><span>")
                    .append(bytes(entry.bytes())).append("</span><span>").append(action).append("</span></div>");
        }
        if (view.entries().isEmpty()) rows.append("<div class='empty'>No entries match this filter.</div>");
        return out + "<div class='intel-summary'><b>" + view.totalEntries() + " entries</b><span>" + bytes(view.bytes())
                + "</span><span>" + esc(view.message()) + "</span></div>" + rows.append("</div>");
    }

    private static String stateMachine(List<IntelligenceManager.StateSnapshot> snapshots, String selected,
            IntelligenceManager.StateDiff diff, boolean canWrite, long serverId) {
        String controls = canWrite ? formStart("intel_state_capture", "change", serverId, "intel-inline-form")
                + "<label><span>Snapshot label</span><input name='label' maxlength='100' required placeholder='Before 1.21 rollout'></label>"
                + submit("add_a_photo", "Capture state", "primary") + "</form>" : readOnly();
        StringBuilder timeline = new StringBuilder("<div class='intel-timeline'>");
        for (IntelligenceManager.StateSnapshot snapshot : snapshots) {
            String href = tabUrl("change", serverId) + "&snapshot=" + url(snapshot.id());
            timeline.append("<a href='").append(href).append("' class='")
                    .append(snapshot.id().equals(selected) ? "is-selected" : "").append("'><i></i><span><strong>")
                    .append(esc(snapshot.label())).append("</strong><small>").append(time(snapshot.createdAt()))
                    .append(" | ").append(snapshot.files()).append(" files | ").append(bytes(snapshot.bytes()))
                    .append("</small></span></a>");
        }
        if (snapshots.isEmpty()) timeline.append("<div class='empty'>No snapshots captured.</div>");
        timeline.append("</div>");
        String restore = canWrite && diff.found() ? formStart("intel_state_restore", "change", serverId, "intel-row-action")
                + hidden("snapshot_id", selected) + "<label class='intel-reason'><span>Restore reason</span><input name='reason' required maxlength='500' placeholder='Why this rollback is necessary'></label>"
                + submit("settings_backup_restore", "Restore selected state", "danger") + "</form>" : "";
        return controls + "<div class='intel-split'><div>" + timeline + "</div><div class='intel-diff'><div class='intel-diff-metrics'>"
                + diffMetric("Changed", diff.changed()) + diffMetric("Added", diff.added()) + diffMetric("Missing", diff.missing())
                + "</div>" + evidence(diff.details(), "Diff details") + restore + "</div></div>";
    }

    private static String diffMetric(String label, int value) {
        return "<span><strong>" + value + "</strong><small>" + esc(label) + "</small></span>";
    }

    private static String performanceRadar(IntelligenceManager.PerformanceRegression regression,
            RuntimeMetrics metrics, boolean canWrite, long serverId) {
        String controls = canWrite ? "<div class='intel-button-pair'>"
                + metricSampleForm("baseline", "Save baseline", metrics, serverId)
                + metricSampleForm("sample", "Record current", metrics, serverId) + "</div>" : "";
        return "<div class='intel-verdict verdict-" + cssToken(regression.status()) + "'><strong>"
                + esc(human(regression.status())) + "</strong><span>" + regression.samples() + " samples</span></div>"
                + "<div class='intel-delta-grid'>" + delta("TPS", regression.tpsDelta())
                + delta("MSPT", regression.msptDelta()) + delta("Memory MiB", regression.memoryDeltaMb()) + "</div>"
                + "<p class='intel-note'>" + esc(regression.message()) + "</p>" + controls;
    }

    private static String metricSampleForm(String label, String text, RuntimeMetrics metrics, long serverId) {
        return formStart("intel_performance_sample", "change", serverId, "intel-row-action")
                + hidden("label", label) + submit("baseline".equals(label) ? "flag" : "add_chart", text,
                        "baseline".equals(label) ? "quiet" : "primary") + "</form>";
    }

    private static String delta(String label, double value) {
        String tone = value > 0.001 ? "up" : value < -0.001 ? "down" : "flat";
        return "<span class='delta-" + tone + "'><small>" + esc(label) + "</small><strong>"
                + (value > 0 ? "+" : "") + format(value, 1) + "</strong></span>";
    }

    private static String dependencyMap(IntelligenceManager.DependencyGraph graph) {
        if (graph.nodes().isEmpty()) return empty("No plugin or mod metadata was found.");
        StringBuilder out = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.DependencyNode node : graph.nodes()) {
            long required = graph.edges().stream().filter(edge -> edge.source().equals(node.id()) && "requires".equals(edge.kind())).count();
            out.append(row("account_tree", node.id(), required + " required | " + node.loader(), node.artifact(),
                    graph.missingRequired().isEmpty() ? "healthy" : "warning"));
        }
        out.append("</div>");
        if (!graph.missingRequired().isEmpty()) out.append(evidence(graph.missingRequired(), "Missing required dependencies"));
        return out.toString();
    }

    private static String playerSearch(String player, List<IntelligenceManager.PlayerIdentity> identities,
            long serverId) {
        String out = "<form method='get' action='/intelligence' class='intel-search'>" + hidden("tab", "players")
                + serverHidden(serverId) + "<span class='material-symbols-outlined'>person_search</span><input name='player' value='"
                + esc(player) + "' maxlength='64' placeholder='Player name or UUID' required><button>Search</button></form>";
        StringBuilder rows = new StringBuilder("<div class='intel-table'><div class='intel-table-head identity-head'><span>Player</span><span>Server</span><span>Sessions</span><span>Last seen</span></div>");
        for (IntelligenceManager.PlayerIdentity identity : identities) {
            rows.append("<a class='intel-table-row identity-row' href='").append(tabUrl("players", serverId))
                    .append("&player=").append(url(identity.name())).append("'><span><strong>")
                    .append(esc(identity.name())).append("</strong><small>").append(esc(identity.uuid()))
                    .append("</small></span><span>").append(esc(identity.source())).append("</span><span>")
                    .append(identity.sessions()).append("</span><span>").append(time(identity.lastJoin())).append("</span></a>");
        }
        if (identities.isEmpty() && !player.isBlank()) rows.append("<div class='empty'>No matching player identity.</div>");
        return out + rows.append("</div>");
    }

    private static String journey(IntelligenceManager.PlayerJourney journey) {
        if (journey.events().isEmpty()) return empty(journey.message());
        StringBuilder out = new StringBuilder("<div class='intel-journey'>");
        for (IntelligenceManager.JourneyEvent event : journey.events()) {
            out.append("<div><i class='journey-").append(cssToken(event.category())).append("'></i><span><small>")
                    .append(time(event.timestamp())).append(" | ").append(esc(human(event.category())))
                    .append("</small><strong>").append(esc(event.title())).append("</strong><p>")
                    .append(esc(event.detail())).append("</p></span></div>");
        }
        return out.append("</div>").toString();
    }

    private static String experience(IntelligenceManager.ExperienceScore score) {
        return "<div class='intel-score score-" + cssToken(score.status()) + "'><div style='--score:"
                + score.score() + "'><strong>" + score.score() + "</strong><span>/100</span></div><section><b>"
                + esc(human(score.status())) + "</b><small>" + score.sessions() + " sessions | "
                + score.abruptSessions() + " abrupt | " + duration(score.averageSessionMillis()) + " average</small></section></div>"
                + evidence(score.signals(), "Experience signals");
    }

    private static String supportInbox(List<IntelligenceManager.SupportCase> cases, boolean canWrite, long serverId) {
        String composer = canWrite ? "<details class='intel-composer'><summary><span class='material-symbols-outlined'>add</span>New support case</summary>"
                + formStart("intel_support_create", "players", serverId, "intel-form-grid")
                + field("Type", select("type", List.of("support", "appeal", "bug", "billing", "other"), "support"))
                + field("Player", "<input name='player' maxlength='64' placeholder='Player or Anonymous'>")
                + fieldWide("Subject", "<input name='subject' maxlength='160' required>")
                + fieldWide("Message", "<textarea name='message' rows='3' maxlength='5000' required></textarea>")
                + submit("send", "Create case", "primary") + "</form></details>" : readOnly();
        StringBuilder rows = new StringBuilder("<div class='intel-accordion'>");
        for (IntelligenceManager.SupportCase item : cases) {
            rows.append("<details><summary><span>").append(badge(item.type())).append("<strong>")
                    .append(esc(item.subject())).append("</strong><small>").append(esc(item.player())).append(" | ")
                    .append(time(item.updatedAt())).append("</small></span>").append(badge(item.status())).append("</summary>")
                    .append("<div class='intel-case-body'><p>").append(esc(item.message())).append("</p>");
            for (IntelligenceManager.SupportReply reply : item.replies()) {
                rows.append("<blockquote><b>").append(esc(reply.author())).append(reply.publicReply() ? " | public" : " | internal")
                        .append("</b><p>").append(esc(reply.message())).append("</p></blockquote>");
            }
            if (canWrite) {
                rows.append("<div class='intel-case-actions'>")
                        .append(formStart("intel_support_update", "players", serverId, "intel-inline-form"))
                        .append(hidden("case_id", item.id())).append("<label><span>Status</span>")
                        .append(select("status", List.of("open", "investigating", "waiting", "resolved", "rejected"), item.status()))
                        .append("</label><label><span>Owner</span><input name='owner' value='").append(esc(item.owner()))
                        .append("' maxlength='64'></label>").append(submit("save", "Update", "quiet")).append("</form>")
                        .append(formStart("intel_support_reply", "players", serverId, "intel-inline-form"))
                        .append(hidden("case_id", item.id())).append("<label class='grow'><span>Reply</span><input name='message' required maxlength='5000'></label>")
                        .append("<label class='intel-check'><input type='checkbox' name='public_reply'><span>Player-visible</span></label>")
                        .append(submit("reply", "Reply", "primary")).append("</form></div>");
            }
            rows.append("</div></details>");
        }
        if (cases.isEmpty()) rows.append("<div class='empty'>No support cases in this view.</div>");
        return composer + rows.append("</div>");
    }

    private static String guardrailEditor(List<IntelligenceManager.Guardrail> rows, boolean canWrite, long serverId) {
        String editor = canWrite ? "<details class='intel-composer' open><summary><span class='material-symbols-outlined'>add</span>Add policy</summary>"
                + formStart("intel_guardrail_save", "policy", serverId, "intel-form-grid")
                + actionPatternField()
                + field("Max online players", "<input type='number' name='max_players' min='-1' max='100000' value='-1'><small>-1 disables this threshold</small>")
                + field("Quiet start", "<input type='number' name='quiet_start' min='-1' max='23' value='-1'>")
                + field("Quiet end", "<input type='number' name='quiet_end' min='-1' max='23' value='-1'>")
                + "<div class='intel-check-grid'>" + checkbox("require_backup", "Fresh backup required")
                + checkbox("require_reason", "Operator reason required") + checkbox("dual_control", "Second operator required") + "</div>"
                + submit("policy", "Save guardrail", "primary") + "</form></details>" : readOnly();
        StringBuilder list = new StringBuilder("<div class='intel-table'><div class='intel-table-head guard-head'><span>Pattern</span><span>Controls</span><span>Created</span><span></span></div>");
        for (IntelligenceManager.Guardrail row : rows) {
            List<String> controls = new ArrayList<>();
            if (row.maxPlayers() >= 0) controls.add("max " + row.maxPlayers() + " players");
            if (row.requireBackup()) controls.add("fresh backup");
            if (row.requireReason()) controls.add("reason");
            if (row.dualControl()) controls.add("dual control");
            if (row.quietStart() >= 0) controls.add(pad(row.quietStart()) + ":00-" + pad(row.quietEnd()) + ":00");
            String remove = canWrite ? formStart("intel_guardrail_delete", "policy", serverId, "intel-row-action")
                    + hidden("guardrail_id", row.id()) + submit("delete", "Delete", "danger") + "</form>" : "";
            list.append("<div class='intel-table-row guard-row'><span class='intel-path'>").append(esc(row.actionPattern()))
                    .append("</span><span>").append(controls.isEmpty() ? "Audit only" : esc(String.join(" | ", controls)))
                    .append("</span><span>").append(time(row.createdAt())).append("</span><span>").append(remove).append("</span></div>");
        }
        if (rows.isEmpty()) list.append("<div class='empty'>No guardrails configured. Unmatched actions continue normally.</div>");
        return editor + list.append("</div>");
    }

    private static String accessGrants(List<IntelligenceManager.TemporaryGrant> grants, boolean canManage,
            long serverId) {
        String editor = canManage ? formStart("intel_jit_grant", "policy", serverId, "intel-stack-form")
                + field("User", "<input name='username' maxlength='64' required>")
                + field("Permission", "<input name='permission' maxlength='160' required placeholder='dash.web.intelligence.read'>")
                + field("Minutes", "<input type='number' name='minutes' min='1' max='1440' value='30' required>")
                + submit("timer", "Grant temporary access", "primary") + "</form>" : readOnly();
        StringBuilder rows = new StringBuilder("<div class='intel-list'>");
        long now = System.currentTimeMillis();
        for (IntelligenceManager.TemporaryGrant grant : grants) {
            boolean active = grant.revokedAt() == 0 && grant.expiresAt() > now;
            String revoke = canManage && active ? formStart("intel_jit_revoke", "policy", serverId, "intel-row-action")
                    + hidden("grant_id", grant.id()) + submit("block", "Revoke", "danger") + "</form>" : "";
            rows.append("<div class='intel-row'><span class='material-symbols-outlined'>timer</span><div><strong>")
                    .append(esc(grant.username())).append("</strong><small>").append(esc(grant.permission())).append(" | ")
                    .append(active ? "expires " + time(grant.expiresAt()) : "inactive").append("</small></div>")
                    .append(revoke).append("</div>");
        }
        if (grants.isEmpty()) rows.append("<div class='empty'>No temporary grants.</div>");
        return editor + rows.append("</div>");
    }

    private static String approvals(List<IntelligenceManager.Approval> approvals, boolean canWrite, long serverId) {
        StringBuilder out = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.Approval approval : approvals) {
            String controls = "";
            if (canWrite && "pending".equals(approval.status())) {
                controls = "<div class='intel-button-pair'>" + approvalForm(approval.id(), true, serverId)
                        + approvalForm(approval.id(), false, serverId) + "</div>";
            }
            out.append("<div class='intel-row intel-row-wrap'><span class='material-symbols-outlined'>approval</span><div><strong>")
                    .append(esc(approval.action())).append("</strong><small>Requested by ").append(esc(approval.requestedBy()))
                    .append(" | ").append(time(approval.createdAt())).append("</small>").append(badge(approval.status()))
                    .append("</div>").append(controls).append("</div>");
        }
        if (approvals.isEmpty()) out.append("<div class='empty'>No dual-control requests.</div>");
        return out.append("</div>").toString();
    }

    private static String approvalForm(String id, boolean approve, long serverId) {
        return formStart("intel_approval_decide", "policy", serverId, "intel-row-action") + hidden("approval_id", id)
                + hidden("decision", approve ? "approve" : "reject")
                + submit(approve ? "check" : "close", approve ? "Approve" : "Reject", approve ? "primary" : "danger") + "</form>";
    }

    private static String retention(IntelligenceManager.RetentionPolicy policy,
            IntelligenceManager.RetentionPreview preview, boolean canWrite, long serverId) {
        String editor = canWrite ? formStart("intel_retention_save", "policy", serverId, "intel-form-grid compact")
                + field("Log days", number("log_days", policy.logDays(), 1, 3650))
                + field("Backup days", number("backup_days", policy.backupDays(), 1, 3650))
                + field("Crash days", number("crash_days", policy.crashDays(), 1, 3650))
                + field("Minimum backups", number("keep_min_backups", policy.keepMinBackups(), 1, 100))
                + submit("save", "Save policy", "quiet") + "</form>" : "";
        String apply = canWrite && preview.files() > 0 ? formStart("intel_retention_apply", "policy", serverId, "intel-row-action")
                + "<label class='intel-reason'><span>Cleanup reason</span><input name='reason' required maxlength='500' placeholder='Routine retention cleanup'></label>"
                + submit("delete_sweep", "Remove previewed files", "danger") + "</form>" : "";
        return "<div class='intel-big-value'><strong>" + bytes(preview.bytes()) + "</strong><span>"
                + preview.files() + " eligible files</span></div>" + editor + evidence(preview.sample(), "Preview sample") + apply;
    }

    private static String configAssistant(List<String> configs, String selected,
            IntelligenceManager.ConfigDocument document, boolean canWrite, long serverId) {
        if (configs.isEmpty()) return empty("No supported configuration files were found.");
        StringBuilder options = new StringBuilder();
        for (String config : configs) options.append(option(config, config, config.equals(selected)));
        String filter = "<form method='get' action='/intelligence' class='intel-filter'>" + hidden("tab", "policy")
                + serverHidden(serverId) + "<label class='grow'><span>Configuration</span><select name='config'>"
                + options + "</select></label>" + filterButton() + "</form>";
        if (document == null) return filter;
        StringBuilder fields = new StringBuilder("<div class='intel-table'><div class='intel-table-head config-head'><span>Key</span><span>Value</span><span>Schema</span><span></span></div>");
        for (IntelligenceManager.ConfigField field : document.fields()) {
            String action = canWrite && document.editable() && field.editable()
                    ? formStart("intel_config_update", "policy", serverId, "intel-config-form")
                            + hidden("path", document.path()) + hidden("key", field.key())
                            + "<input name='value' value='" + esc(field.value()) + "' maxlength='4000' aria-label='New value for "
                            + esc(field.key()) + "'>" + submit("save", "Save", "quiet") + "</form>"
                    : "<span class='intel-path'>" + esc(field.value()) + "</span>";
            fields.append("<div class='intel-table-row config-row'><span class='intel-path'>").append(esc(field.key()))
                    .append("</span><span>").append(action).append("</span><span><b>").append(esc(field.type()))
                    .append("</b><small>").append(esc(field.constraint())).append("</small></span><span></span></div>");
        }
        if (document.fields().isEmpty()) fields.append("<div class='empty'>No scalar fields could be inferred.</div>");
        return filter + evidence(document.warnings(), "Safety notes") + fields.append("</div>");
    }

    private static String universalSearch(String search, List<IntelligenceManager.SearchHit> hits, long serverId) {
        String form = "<form method='get' action='/intelligence' class='intel-search'>" + hidden("tab", "supply")
                + serverHidden(serverId) + "<span class='material-symbols-outlined'>search</span><input name='q' value='"
                + esc(search) + "' maxlength='160' placeholder='Player, incident, config or plugin' required><button>Search fleet</button></form>";
        StringBuilder rows = new StringBuilder("<div class='intel-table'><div class='intel-table-head search-head'><span>Result</span><span>Type</span><span>Server</span><span>Score</span></div>");
        for (IntelligenceManager.SearchHit hit : hits) {
            rows.append("<div class='intel-table-row search-row'><span><strong>").append(esc(hit.title()))
                    .append("</strong><small>").append(esc(hit.reference())).append("</small></span><span>")
                    .append(badge(hit.type())).append("</span><span>").append(esc(hit.server())).append("</span><span>")
                    .append(hit.score()).append("</span></div>");
        }
        if (hits.isEmpty() && !search.isBlank()) rows.append("<div class='empty'>No ranked result for this query.</div>");
        return form + rows.append("</div>");
    }

    private static String attribution(List<IntelligenceManager.ResourceAttribution> rows) {
        if (rows.isEmpty()) return empty("No runtime artifacts found.");
        StringBuilder out = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.ResourceAttribution item : rows) {
            out.append("<div class='intel-resource'><div><strong>").append(esc(item.artifact())).append("</strong>")
                    .append(badge(item.confidence())).append("</div><div class='intel-progress'><i style='width:")
                    .append(item.score()).append("%'></i></div><small>").append(item.score()).append(" impact score | ")
                    .append(item.classes()).append(" classes | ").append(item.logSignals()).append(" log signals</small></div>");
        }
        return out.append("</div>").toString();
    }

    private static String supplyChain(List<IntelligenceManager.SupplyArtifact> rows) {
        if (rows.isEmpty()) return empty("No runtime artifacts found.");
        StringBuilder out = new StringBuilder("<div class='intel-accordion'>");
        for (IntelligenceManager.SupplyArtifact item : rows) {
            out.append("<details><summary><span><strong>").append(esc(item.fileName())).append("</strong><small>")
                    .append(bytes(item.bytes())).append(" | ").append(esc(item.loader())).append(" | ")
                    .append(item.entries()).append(" entries</small></span>").append(badge(item.trust())).append("</summary>")
                    .append("<div class='intel-case-body'><code>SHA-256 ").append(esc(item.sha256())).append("</code>")
                    .append(evidence(item.warnings(), "Trust findings")).append("</div></details>");
        }
        return out.append("</div>").toString();
    }

    private static String worldHealth(IntelligenceManager.WorldHealth world) {
        StringBuilder findings = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.WorldFinding finding : world.findings()) {
            findings.append(row("warning", finding.path(), finding.message(), human(finding.severity()), finding.severity()));
        }
        if (world.findings().isEmpty()) findings.append("<div class='empty'>All scanned region headers are structurally valid.</div>");
        return "<div class='intel-verdict verdict-" + cssToken(world.status()) + "'><strong>"
                + esc(human(world.status())) + "</strong><span>" + world.invalidRegions() + " invalid</span></div>"
                + "<div class='intel-diff-metrics'>" + diffMetric("Worlds", world.worlds())
                + diffMetric("Regions", world.regionFiles()) + diffMetric("Storage", (int) Math.min(Integer.MAX_VALUE, world.bytes() / 1_048_576L))
                + "</div>" + findings.append("</div>");
    }

    private static String storage(IntelligenceManager.StorageReport storage) {
        StringBuilder rows = new StringBuilder("<div class='intel-table'><div class='intel-table-head storage-head'><span>Area</span><span>Category</span><span>Size</span><span>Cleanup</span></div>");
        for (IntelligenceManager.StorageBucket bucket : storage.buckets()) {
            rows.append("<div class='intel-table-row storage-row'><span class='intel-path'>").append(esc(bucket.name()))
                    .append("</span><span>").append(badge(bucket.category())).append("</span><span>")
                    .append(bytes(bucket.bytes())).append("</span><span>")
                    .append(bucket.cleanupCandidate() ? "Policy-managed" : "Protected").append("</span></div>");
        }
        return "<div class='intel-storage-summary'><span><small>Managed</small><strong>" + bytes(storage.managedBytes())
                + "</strong></span><span><small>Free volume</small><strong>" + bytes(storage.usableBytes())
                + "</strong></span><span><small>Reclaimable</small><strong>" + bytes(storage.reclaimableBytes())
                + "</strong></span></div>" + rows.append("</div>");
    }

    private static String serviceLevel(IntelligenceManager.ServiceLevel service, RuntimeMetrics metrics,
            boolean canWrite, long serverId) {
        String sample = canWrite ? actionButton("intel_service_sample", "reliability", serverId, "add_chart", "Record live sample", "primary") : "";
        return "<div class='intel-score score-" + cssToken(service.status()) + "'><div style='--score:"
                + (int) Math.round(service.availabilityPercent()) + "'><strong>" + format(service.availabilityPercent(), 2)
                + "</strong><span>%</span></div><section><b>" + esc(human(service.status())) + "</b><small>"
                + service.samples() + " samples in 30 days</small></section></div>"
                + "<div class='intel-delta-grid'>" + simpleValue("Avg TPS", format(service.averageTps(), 1))
                + simpleValue("Avg MSPT", format(service.averageMspt(), 1))
                + simpleValue("Backup age", format(service.averageBackupAgeHours(), 1) + "h") + "</div>" + sample;
    }

    private static String simpleValue(String label, String value) {
        return "<span><small>" + esc(label) + "</small><strong>" + esc(value) + "</strong></span>";
    }

    private static String warRooms(List<IntelligenceManager.WarRoom> rooms, boolean canWrite, long serverId) {
        String create = canWrite ? "<details class='intel-composer'><summary><span class='material-symbols-outlined'>add</span>Open war room</summary>"
                + formStart("intel_war_create", "response", serverId, "intel-form-grid")
                + field("Title", "<input name='title' required maxlength='160'>")
                + field("Severity", select("severity", List.of("info", "warning", "critical"), "warning"))
                + fieldWide("Situation summary", "<textarea name='summary' rows='3' required maxlength='4000'></textarea>")
                + submit("emergency_home", "Open war room", "danger") + "</form></details>" : readOnly();
        StringBuilder rows = new StringBuilder("<div class='intel-accordion'>");
        for (IntelligenceManager.WarRoom room : rooms) {
            rows.append("<details ").append("open".equals(room.status()) ? "open" : "").append("><summary><span>")
                    .append(badge(room.severity())).append("<strong>").append(esc(room.title())).append("</strong><small>Commander ")
                    .append(esc(room.commander())).append(" | ").append(time(room.createdAt())).append("</small></span>")
                    .append(badge(room.status())).append("</summary><div class='intel-case-body'><p>")
                    .append(esc(room.summary())).append("</p><div class='intel-war-timeline'>");
            for (IntelligenceManager.WarRoomUpdate update : room.updates()) {
                rows.append("<div><b>").append(esc(human(update.kind()))).append(" | ").append(esc(update.author()))
                        .append("</b><span>").append(esc(update.message())).append("</span><small>")
                        .append(time(update.createdAt())).append("</small></div>");
            }
            rows.append("</div>");
            if (canWrite && "open".equals(room.status())) {
                rows.append("<div class='intel-case-actions'>")
                        .append(formStart("intel_war_update", "response", serverId, "intel-inline-form"))
                        .append(hidden("room_id", room.id())).append("<label class='grow'><span>Timeline update</span><input name='message' required maxlength='4000'></label>")
                        .append("<label><span>Kind</span>").append(select("kind", List.of("update", "decision", "action", "resolution"), "update")).append("</label>")
                        .append(checkbox("publish", "Publish")).append(submit("send", "Add update", "primary")).append("</form>")
                        .append(formStart("intel_war_close", "response", serverId, "intel-inline-form"))
                        .append(hidden("room_id", room.id())).append("<label class='grow'><span>Resolution</span><input name='resolution' required maxlength='4000'></label>")
                        .append(submit("check_circle", "Close incident", "quiet")).append("</form></div>");
            }
            rows.append("</div></details>");
        }
        if (rooms.isEmpty()) rows.append("<div class='empty'>No incident war rooms.</div>");
        return create + rows.append("</div>");
    }

    private static String statusEditor(IntelligenceManager.PublicStatus status, boolean canWrite, long serverId) {
        String editor = canWrite ? formStart("intel_status_update", "response", serverId, "intel-form-grid")
                + field("Component", "<input name='component' maxlength='100' required placeholder='Authentication'>")
                + field("State", select("status", List.of("operational", "degraded", "partial-outage", "major-outage", "maintenance"), "operational"))
                + fieldWide("Public message", "<input name='message' maxlength='500' placeholder='Short, sanitized service update'>")
                + submit("public", "Publish component", "primary") + "</form>" : readOnly();
        StringBuilder components = new StringBuilder("<div class='intel-list'>");
        for (IntelligenceManager.StatusComponent component : status.components()) {
            components.append(row("circle", component.component(), component.message(), human(component.status()), component.status()));
        }
        return "<div class='intel-public-overall verdict-" + cssToken(status.overall()) + "'><i></i><span><small>Overall status</small><strong>"
                + esc(human(status.overall())) + "</strong></span><a href='/status" + (serverId > 0 ? "?id=" + serverId : "")
                + "' target='_blank' rel='noopener'>Open public page</a></div>" + editor + components.append("</div>");
    }

    private static String actionButton(String action, String tab, long serverId, String icon, String label,
            String tone) {
        return formStart(action, tab, serverId, "intel-action-form") + submit(icon, label, tone) + "</form>";
    }

    private static String formStart(String action, String tab, long serverId, String css) {
        return "<form method='post' action='/action' class='" + css + "' data-intel-form>"
                + hidden("action", action) + hidden("return_to", tabUrl(tab, serverId)) + serverHidden(serverId);
    }

    private static String submit(String icon, String label, String tone) {
        return "<button class='intel-button button-" + cssToken(tone) + "'><span class='intel-button-label'><span class='material-symbols-outlined'>"
                + icon + "</span>" + esc(label) + "</span><span class='intel-spinner' aria-hidden='true'></span></button>";
    }

    private static String filterButton() {
        return "<button class='intel-button button-quiet'><span class='material-symbols-outlined'>filter_alt</span>Apply</button>";
    }

    private static String field(String label, String control) {
        return "<label><span>" + esc(label) + "</span>" + control + "</label>";
    }

    private static String actionPatternField() {
        return "<label><span class='intel-field-label'>Action pattern <details class='intel-pattern-help'><summary aria-label='Show action pattern examples' title='Action pattern examples'>?</summary><div><b>Match an exact action</b><code>restart</code><b>Match an action family</b><code>delete_*</code><code>intel_*</code><b>Match every guarded action</b><code>*</code><small>Only the asterisk is a wildcard. Letters, numbers, dots, underscores and hyphens are accepted. Use the narrowest pattern possible.</small></div></details></span><input name='action_pattern' maxlength='120' pattern='[A-Za-z0-9_*.-]+' required placeholder='restart or delete_*'></label>";
    }

    private static String fieldWide(String label, String control) {
        return "<label class='intel-field-wide'><span>" + esc(label) + "</span>" + control + "</label>";
    }

    private static String checkbox(String name, String label) {
        return "<label class='intel-check'><input type='checkbox' name='" + esc(name) + "'><span>" + esc(label) + "</span></label>";
    }

    private static String number(String name, int value, int min, int max) {
        return "<input type='number' name='" + esc(name) + "' value='" + value + "' min='" + min + "' max='" + max + "' required>";
    }

    private static String select(String name, List<String> values, String selected) {
        StringBuilder out = new StringBuilder("<select name='").append(esc(name)).append("'>");
        for (String value : values) out.append(option(value, human(value), value.equals(selected)));
        return out.append("</select>").toString();
    }

    private static String option(String value, String label, boolean selected) {
        return "<option value='" + esc(value) + "'" + (selected ? " selected" : "") + ">" + esc(label) + "</option>";
    }

    private static String hidden(String name, String value) {
        return "<input type='hidden' name='" + esc(name) + "' value='" + esc(value) + "'>";
    }

    private static String serverHidden(long serverId) {
        return serverId > 0 ? hidden("id", Long.toString(serverId)) : "";
    }

    private static String card(String title, String icon, String description, String body) {
        return "<article class='intel-card'><header><span class='material-symbols-outlined'>" + icon
                + "</span><div><h2>" + esc(title) + "</h2><p>" + esc(description)
                + "</p></div><details class='intel-help intel-card-help'><summary aria-label='Explain " + esc(title)
                + "'>?</summary><p>" + esc(description) + "</p></details></header><div class='intel-card-body'>"
                + body + "</div></article>";
    }

    private static String cardWide(String title, String icon, String description, String body) {
        return card(title, icon, description, body).replaceFirst("intel-card'", "intel-card intel-card-wide'");
    }

    private static String row(String icon, String title, String detail, String meta, String tone) {
        return "<div class='intel-row'><span class='material-symbols-outlined tone-icon-" + cssToken(tone) + "'>" + icon
                + "</span><div><strong>" + esc(title) + "</strong><small>" + esc(detail) + "</small></div><span>"
                + esc(meta) + "</span></div>";
    }

    private static String badge(String value) {
        return "<span class='intel-badge badge-" + cssToken(value) + "'>" + esc(human(value)) + "</span>";
    }

    private static String empty(String message) {
        return "<div class='empty'><span class='material-symbols-outlined'>inbox</span>" + esc(message) + "</div>";
    }

    private static String readOnly() {
        return "<div class='intel-readonly'><span class='material-symbols-outlined'>lock</span>Read-only access</div>";
    }

    private static String serverSelector(long selected, List<ServerOption> servers, String tab) {
        if (servers == null || servers.isEmpty()) return "";
        StringBuilder options = new StringBuilder();
        for (ServerOption server : servers) {
            options.append(option(Long.toString(server.id()), server.name() + (server.online() ? " | online" : " | offline"), server.id() == selected));
        }
        return "<form method='get' action='/intelligence' class='intel-server-select'>" + hidden("tab", tab)
                + "<span class='material-symbols-outlined'>dns</span><select name='id' aria-label='Server' onchange='this.form.requestSubmit()'>"
                + options + "</select></form>";
    }

    private static String publicServerSelector(long selected, List<ServerOption> servers) {
        if (servers == null || servers.size() < 2) return "";
        StringBuilder options = new StringBuilder();
        for (ServerOption server : servers) options.append(option(Long.toString(server.id()), server.name(), server.id() == selected));
        return "<form method='get' action='/status'><select name='id' aria-label='Server' onchange='this.form.submit()'>"
                + options + "</select></form>";
    }

    private static String tabUrl(String tab, long serverId) {
        return "/intelligence?tab=" + url(tab) + (serverId > 0 ? "&id=" + serverId : "");
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String clean(String value, int max, String fallback) {
        String safe = value == null ? "" : value.trim();
        if (safe.length() > max) safe = safe.substring(0, max);
        return safe.isBlank() ? fallback : safe;
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String human(String value) {
        String safe = value == null || value.isBlank() ? "Unknown" : value.replace('-', ' ').replace('_', ' ');
        String[] parts = safe.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String cssToken(String value) {
        String safe = value == null ? "neutral" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        return safe.isBlank() ? "neutral" : safe;
    }

    private static String time(long timestamp) {
        return timestamp <= 0 ? "Never" : DATE_TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String bytes(long value) {
        double size = Math.max(0L, value);
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (size >= 1024.0 && unit < units.length - 1) { size /= 1024.0; unit++; }
        return format(size, unit == 0 ? 0 : 1) + " " + units[unit];
    }

    private static String duration(long millis) {
        if (millis <= 0) return "0m";
        long minutes = millis / 60_000L;
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    private static String format(double value, int decimals) {
        if (!Double.isFinite(value)) return "n/a";
        return String.format(Locale.ROOT, "%." + Math.max(0, decimals) + "f", value);
    }

    private static String percent(double value) {
        return format(Math.max(0.0, Math.min(1.0, value)) * 100.0, 0) + "%";
    }

    private static String pad(int hour) {
        return String.format(Locale.ROOT, "%02d", Math.max(0, Math.min(23, hour)));
    }

    private static String styles() {
        return "<style>"
                + ".intel-main{--intel-ease:cubic-bezier(.16,1,.3,1);--intel-border:rgba(100,116,139,.3);--intel-surface:rgba(15,23,42,.56);color:#e2e8f0}"
                + ".intel-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:1.25rem;padding:1.25rem;border:1px solid var(--intel-border);background:linear-gradient(135deg,rgba(8,47,73,.56),rgba(15,23,42,.72) 48%,rgba(6,78,59,.28));border-radius:8px;overflow:hidden;position:relative}"
                + ".intel-hero:after{content:'';position:absolute;inset:auto -10% -75% 45%;height:220px;background:radial-gradient(ellipse,rgba(34,211,238,.12),transparent 68%);pointer-events:none}.intel-hero-copy,.intel-hero-actions{position:relative;z-index:1}.intel-kicker{display:inline-flex;align-items:center;gap:.4rem;color:#67e8f9;font-size:.72rem;font-weight:800;text-transform:uppercase;letter-spacing:.08em}.intel-kicker .material-symbols-outlined{font-size:17px}.intel-hero h1{margin:.55rem 0 .3rem;max-width:780px;font-size:clamp(1.4rem,2.5vw,2.15rem);line-height:1.12;font-weight:800;color:#fff;letter-spacing:0}.intel-hero p{font-size:.83rem;color:#94a3b8}.intel-hero-actions{display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:.55rem;min-width:min(100%,330px)}"
                + ".intel-server-select,.intel-status-link,.intel-live{height:40px;display:inline-flex;align-items:center;gap:.45rem;border:1px solid var(--intel-border);background:rgba(2,6,23,.58);padding:0 .7rem;border-radius:7px;font-size:.75rem;font-weight:700;color:#cbd5e1}.intel-server-select select{border:0!important;background:transparent!important;padding:.2rem 1.6rem .2rem .1rem!important;min-width:140px}.intel-server-select .material-symbols-outlined,.intel-status-link .material-symbols-outlined{font-size:17px;color:#67e8f9}.intel-live i{width:7px;height:7px;border-radius:50%;background:#fb7185}.intel-live.is-online i{background:#34d399;box-shadow:0 0 0 5px rgba(52,211,153,.12);animation:intelPulse 2.4s ease-out infinite}"
                + ".intel-banner{display:flex;align-items:center;gap:.6rem;border:1px solid rgba(34,211,238,.3);background:rgba(6,182,212,.1);padding:.75rem .9rem;border-radius:7px;color:#cffafe;font-size:.8rem}.intel-banner .material-symbols-outlined{font-size:18px}"
                + ".intel-metrics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:.6rem}.intel-metric{position:relative;display:flex;align-items:center;gap:.7rem;min-width:0;padding:.75rem 2.1rem .75rem .85rem;border:1px solid var(--intel-border);background:var(--intel-surface);border-radius:7px}.intel-metric>.material-symbols-outlined{font-size:20px;color:#94a3b8}.intel-metric small,.intel-metric strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.intel-metric small{font-size:.64rem;color:#64748b;text-transform:uppercase;letter-spacing:.05em}.intel-metric strong{margin-top:.1rem;font-size:1rem;color:#fff}.intel-metric.tone-good>.material-symbols-outlined{color:#34d399}.intel-metric.tone-watch>.material-symbols-outlined{color:#fbbf24}.intel-help{position:absolute;right:.6rem;top:.6rem;z-index:25}.intel-help summary{display:grid;place-items:center;width:19px;height:19px;border:1px solid rgba(100,116,139,.4);border-radius:50%;color:#94a3b8;font-size:11px;font-weight:800;cursor:pointer;list-style:none}.intel-help summary::-webkit-details-marker{display:none}.intel-help[open] summary{border-color:#22d3ee;color:#67e8f9;background:rgba(34,211,238,.08)}.intel-help>p{position:absolute;right:0;top:24px;width:min(260px,75vw);margin:0!important;border:1px solid rgba(100,116,139,.42);border-radius:7px;background:#0b1423;padding:.65rem .75rem;color:#cbd5e1!important;font-size:.66rem!important;line-height:1.45;box-shadow:0 18px 38px rgba(0,0,0,.45)}"
                + ".intel-workspace{min-width:0}"
                + ".intel-workspace{view-transition-name:intelligence-workspace}.intel-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem}.intel-card{min-width:0;border:1px solid var(--intel-border);background:var(--intel-surface);border-radius:8px;overflow:visible;content-visibility:auto;contain-intrinsic-size:360px;animation:intelCardIn .34s var(--intel-ease) both}.intel-card:nth-child(2){animation-delay:30ms}.intel-card:nth-child(3){animation-delay:60ms}.intel-card:nth-child(4){animation-delay:90ms}.intel-card:nth-child(5){animation-delay:120ms}.intel-card-wide{grid-column:1/-1}.intel-card>header{position:relative;display:flex;gap:.75rem;align-items:flex-start;padding:.9rem 3rem .9rem 1rem;border-bottom:1px solid rgba(100,116,139,.2)}.intel-card>header>.material-symbols-outlined{display:grid;place-items:center;width:34px;height:34px;flex:0 0 auto;border-radius:7px;background:rgba(34,211,238,.1);color:#67e8f9;font-size:19px}.intel-card h2{font-size:.92rem;font-weight:800;color:#fff}.intel-card header p{margin-top:.18rem;font-size:.71rem;line-height:1.45;color:#64748b}.intel-card-body{padding:1rem}.intel-card-body>*+*{margin-top:.8rem}.intel-card-help{right:1rem;top:1rem}"
                + ".intel-main label{display:flex;flex-direction:column;gap:.3rem;min-width:0}.intel-main label>span{font-size:.66rem;font-weight:700;color:#94a3b8}.intel-main input:not([type=checkbox]),.intel-main textarea,.intel-main select{width:100%;min-height:38px;border:1px solid rgba(100,116,139,.4);background:rgba(2,6,23,.62);border-radius:6px;padding:.55rem .65rem;color:#e2e8f0;font-size:.76rem;outline:none;transition:border-color .2s,box-shadow .2s}.intel-main textarea{resize:vertical}.intel-main input:focus,.intel-main textarea:focus,.intel-main select:focus{border-color:#22d3ee;box-shadow:0 0 0 3px rgba(34,211,238,.1)}.intel-main label small{font-size:.61rem;color:#475569}"
                + ".intel-button{display:inline-flex;align-items:center;justify-content:center;gap:.4rem;min-height:38px;border:1px solid transparent;border-radius:6px;padding:.52rem .8rem;font-size:.72rem;font-weight:800;transition:transform .18s var(--intel-ease),filter .18s,border-color .18s}.intel-button:hover{transform:translateY(-1px);filter:brightness(1.08)}.intel-button-label{display:inline-flex;align-items:center;gap:.4rem}.intel-button .material-symbols-outlined{font-size:17px}.button-primary{background:#22d3ee;color:#06202a}.button-quiet{border-color:rgba(100,116,139,.45);background:rgba(30,41,59,.66);color:#e2e8f0}.button-danger{border-color:rgba(244,63,94,.35);background:rgba(244,63,94,.12);color:#fda4af}.intel-spinner{display:none;width:15px;height:15px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:intelSpin .65s linear infinite}.is-submitting .intel-button-label{opacity:.65}.is-submitting .intel-spinner{display:block}.is-submitting .intel-button{pointer-events:none}"
                + ".intel-action-form{display:flex}.intel-inline-form{display:flex;align-items:flex-end;flex-wrap:wrap;gap:.55rem}.intel-inline-form>label{flex:1 1 160px}.intel-stack-form{display:grid;gap:.6rem}.intel-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.6rem}.intel-field-wide,.intel-form-grid>.intel-button,.intel-form-grid>.intel-check-grid{grid-column:1/-1}.intel-form-grid.compact{grid-template-columns:repeat(2,minmax(0,1fr))}.intel-filter{display:flex;align-items:flex-end;gap:.55rem;flex-wrap:wrap}.intel-filter label{flex:1 1 210px}.intel-search{display:flex;align-items:center;gap:.5rem;border:1px solid rgba(100,116,139,.35);background:rgba(2,6,23,.45);border-radius:7px;padding:.45rem}.intel-search>.material-symbols-outlined{margin-left:.35rem;color:#67e8f9}.intel-search input{border:0!important;background:transparent!important;box-shadow:none!important}.intel-search button{height:36px;white-space:nowrap;border-radius:6px;background:#22d3ee;padding:0 .85rem;color:#06202a;font-size:.72rem;font-weight:800}.intel-button-pair{display:flex;align-items:center;gap:.45rem;flex-wrap:wrap}.grow{flex:1 1 220px!important}.intel-reason{min-width:220px}.intel-field-label{display:flex!important;align-items:center;gap:.35rem}.intel-pattern-help{position:relative;display:inline-block}.intel-pattern-help summary{display:grid;place-items:center;width:17px;height:17px;border:1px solid rgba(34,211,238,.4);border-radius:50%;color:#67e8f9;font-size:10px;cursor:pointer;list-style:none}.intel-pattern-help summary::-webkit-details-marker{display:none}.intel-pattern-help>div{position:absolute;left:0;top:24px;z-index:30;display:grid;width:min(300px,75vw);gap:.32rem;border:1px solid rgba(100,116,139,.4);border-radius:7px;background:#0f172a;padding:.75rem;box-shadow:0 18px 45px rgba(0,0,0,.45)}.intel-pattern-help b{font-size:.62rem;color:#cbd5e1}.intel-pattern-help code{border-radius:4px;background:#020617;padding:.25rem .4rem;color:#67e8f9;font-size:.65rem}.intel-pattern-help small{margin-top:.25rem;font-size:.6rem;line-height:1.45;color:#94a3b8}"
                + ".intel-check-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.45rem}.intel-check{display:flex!important;flex-direction:row!important;align-items:center!important;gap:.45rem!important;min-height:36px;border:1px solid rgba(100,116,139,.25);background:rgba(2,6,23,.35);padding:.5rem .6rem;border-radius:6px}.intel-check input{accent-color:#22d3ee}.intel-check span{font-size:.68rem!important;color:#cbd5e1!important}.intel-composer{border:1px solid rgba(34,211,238,.2);border-radius:7px;background:rgba(8,145,178,.06)}.intel-composer>summary{display:flex;align-items:center;gap:.4rem;padding:.65rem .75rem;cursor:pointer;color:#a5f3fc;font-size:.72rem;font-weight:800}.intel-composer>summary .material-symbols-outlined{font-size:17px}.intel-composer>form{padding:0 .75rem .75rem}"
                + ".intel-list{display:grid;gap:.45rem;max-height:400px;overflow:auto;overscroll-behavior:contain}.intel-row{display:flex;align-items:center;gap:.65rem;min-width:0;padding:.62rem .7rem;border:1px solid rgba(100,116,139,.23);background:rgba(2,6,23,.34);border-radius:6px}.intel-row>.material-symbols-outlined{font-size:18px;color:#67e8f9}.intel-row>div{min-width:0;flex:1}.intel-row strong,.intel-row small{display:block}.intel-row strong{overflow:hidden;text-overflow:ellipsis;font-size:.75rem;color:#e2e8f0}.intel-row small{margin-top:.15rem;font-size:.64rem;color:#64748b;line-height:1.35}.intel-row>span:last-child{font-size:.64rem;color:#94a3b8}.intel-row-wrap{align-items:flex-start;flex-wrap:wrap}.intel-row-action{display:inline-flex;align-items:flex-end;gap:.45rem}.intel-row-action .intel-button{min-height:31px;padding:.38rem .55rem}.intel-readonly{display:flex;align-items:center;gap:.4rem;color:#64748b;font-size:.7rem}.intel-readonly .material-symbols-outlined{font-size:16px}.empty{display:flex;align-items:center;justify-content:center;gap:.45rem;min-height:62px;padding:.75rem;text-align:center;color:#64748b;font-size:.71rem}.empty .material-symbols-outlined{font-size:18px}"
                + ".intel-verdict{display:flex;align-items:center;justify-content:space-between;gap:.8rem;border-left:3px solid #64748b;background:rgba(100,116,139,.08);padding:.7rem .8rem;border-radius:5px}.intel-verdict strong{font-size:.77rem;color:#e2e8f0}.intel-verdict span{font-size:.64rem;font-weight:800;text-transform:uppercase;color:#94a3b8}.verdict-critical,.verdict-major-outage,.verdict-regression,.verdict-degraded{border-color:#fb7185;background:rgba(244,63,94,.09)}.verdict-warning,.verdict-watch,.verdict-at-risk,.verdict-partial-outage,.verdict-learning,.verdict-maintenance{border-color:#fbbf24;background:rgba(245,158,11,.08)}.verdict-passed,.verdict-stable,.verdict-healthy,.verdict-meeting,.verdict-operational,.verdict-excellent{border-color:#34d399;background:rgba(16,185,129,.08)}.intel-evidence>b{font-size:.67rem;color:#94a3b8}.intel-evidence ul{display:grid;gap:.32rem;margin-top:.4rem}.intel-evidence li{position:relative;padding-left:.8rem;font-size:.68rem;line-height:1.45;color:#94a3b8}.intel-evidence li:before{content:'';position:absolute;left:0;top:.48rem;width:4px;height:4px;border-radius:50%;background:#22d3ee}.intel-note{font-size:.7rem;line-height:1.5;color:#94a3b8}"
                + ".intel-summary,.intel-storage-summary{display:flex;align-items:center;flex-wrap:wrap;gap:.8rem;color:#64748b;font-size:.68rem}.intel-summary b{color:#cbd5e1}.intel-table{min-width:0;overflow-x:auto;border:1px solid rgba(100,116,139,.2);border-radius:7px}.intel-table-head,.intel-table-row{display:grid;grid-template-columns:minmax(220px,1.6fr) minmax(100px,.7fr) minmax(80px,.5fr) minmax(70px,.35fr);align-items:center;gap:.65rem;min-width:620px;padding:.58rem .7rem}.intel-table-head{background:rgba(2,6,23,.54);color:#64748b;font-size:.62rem;font-weight:800;text-transform:uppercase}.intel-table-row{border-top:1px solid rgba(100,116,139,.16);font-size:.69rem;color:#94a3b8}.intel-table-row strong,.intel-table-row small{display:block}.intel-table-row strong{color:#e2e8f0}.intel-table-row small{margin-top:.15rem;color:#64748b}.intel-path{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;overflow-wrap:anywhere;color:#cbd5e1}.identity-head,.identity-row{grid-template-columns:minmax(210px,1.4fr) minmax(100px,.6fr) 70px 120px}.guard-head,.guard-row{grid-template-columns:minmax(160px,.7fr) minmax(220px,1.3fr) 120px 80px}.config-head,.config-row{grid-template-columns:minmax(170px,.7fr) minmax(240px,1.2fr) minmax(170px,.8fr) 20px}.search-head,.search-row{grid-template-columns:minmax(220px,1.4fr) 110px minmax(110px,.6fr) 55px}.storage-head,.storage-row{grid-template-columns:minmax(190px,1fr) 120px 100px 110px}.intel-config-form{display:flex;align-items:center;gap:.4rem}.intel-config-form input{min-width:150px}.intel-config-form .intel-button{min-height:34px;padding:.35rem .5rem}"
                + ".intel-timeline{display:grid;gap:.4rem;max-height:330px;overflow:auto}.intel-timeline a{display:flex;align-items:flex-start;gap:.55rem;padding:.55rem;border:1px solid rgba(100,116,139,.22);border-radius:6px}.intel-timeline a>i{width:8px;height:8px;margin-top:.27rem;flex:0 0 auto;border:2px solid #64748b;border-radius:50%}.intel-timeline a.is-selected{border-color:rgba(34,211,238,.45);background:rgba(8,145,178,.09)}.intel-timeline a.is-selected>i{border-color:#22d3ee;background:#22d3ee;box-shadow:0 0 0 4px rgba(34,211,238,.1)}.intel-timeline strong,.intel-timeline small{display:block}.intel-timeline strong{font-size:.72rem;color:#e2e8f0}.intel-timeline small{margin-top:.15rem;font-size:.62rem;color:#64748b}.intel-split{display:grid;grid-template-columns:minmax(230px,.8fr) minmax(280px,1.2fr);gap:.8rem}.intel-diff{min-width:0}.intel-diff-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.4rem}.intel-diff-metrics>span{padding:.55rem;border:1px solid rgba(100,116,139,.22);border-radius:6px;text-align:center}.intel-diff-metrics strong,.intel-diff-metrics small{display:block}.intel-diff-metrics strong{font-size:1rem;color:#fff}.intel-diff-metrics small{font-size:.6rem;color:#64748b}.intel-big-value{display:flex;align-items:baseline;gap:.55rem}.intel-big-value strong{font-size:1.4rem;color:#fff}.intel-big-value span{font-size:.68rem;color:#64748b}.intel-progress{height:5px;overflow:hidden;border-radius:4px;background:rgba(100,116,139,.17)}.intel-progress i{display:block;height:100%;border-radius:inherit;background:linear-gradient(90deg,#0891b2,#22d3ee,#34d399);transform-origin:left;animation:intelGrow .65s var(--intel-ease) both}.intel-delta-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.4rem}.intel-delta-grid>span{padding:.55rem;border:1px solid rgba(100,116,139,.2);border-radius:6px}.intel-delta-grid small,.intel-delta-grid strong{display:block}.intel-delta-grid small{font-size:.6rem;color:#64748b}.intel-delta-grid strong{margin-top:.12rem;font-size:.82rem;color:#e2e8f0}.delta-up strong{color:#fb7185!important}.delta-down strong{color:#34d399!important}"
                + ".intel-journey{display:grid;gap:0;max-height:420px;overflow:auto}.intel-journey>div{display:flex;gap:.65rem;position:relative;padding:0 0 .8rem}.intel-journey>div:before{content:'';position:absolute;left:4px;top:10px;bottom:0;width:1px;background:rgba(100,116,139,.35)}.intel-journey i{position:relative;z-index:1;width:9px;height:9px;margin-top:5px;flex:0 0 auto;border-radius:50%;background:#22d3ee;box-shadow:0 0 0 4px #0f172a}.intel-journey strong,.intel-journey small{display:block}.intel-journey small{font-size:.6rem;color:#64748b}.intel-journey strong{margin-top:.1rem;font-size:.73rem;color:#e2e8f0}.intel-journey p{margin-top:.18rem;font-size:.66rem;color:#94a3b8}.journey-guardian{background:#fbbf24!important}.journey-support{background:#c084fc!important}.journey-staff{background:#fb7185!important}.intel-score{display:flex;align-items:center;gap:.8rem}.intel-score>div{--score:0;display:grid;place-content:center;width:72px;height:72px;flex:0 0 auto;border-radius:50%;background:radial-gradient(circle at center,#0f172a 57%,transparent 59%),conic-gradient(#22d3ee calc(var(--score)*1%),rgba(100,116,139,.2) 0)}.intel-score>div strong{font-size:1rem;color:#fff}.intel-score>div span{font-size:.55rem;color:#64748b;text-align:center}.intel-score section b,.intel-score section small{display:block}.intel-score section b{font-size:.85rem;color:#e2e8f0}.intel-score section small{margin-top:.2rem;font-size:.65rem;color:#64748b}"
                + ".intel-accordion{display:grid;gap:.45rem;max-height:560px;overflow:auto}.intel-accordion>details{border:1px solid rgba(100,116,139,.23);border-radius:6px;background:rgba(2,6,23,.28)}.intel-accordion>details>summary{display:flex;align-items:center;justify-content:space-between;gap:.7rem;padding:.65rem .7rem;cursor:pointer}.intel-accordion summary>span:first-child{min-width:0}.intel-accordion summary strong,.intel-accordion summary small{display:block}.intel-accordion summary strong{margin-top:.22rem;font-size:.73rem;color:#e2e8f0}.intel-accordion summary small{margin-top:.13rem;font-size:.61rem;color:#64748b}.intel-case-body{padding:.15rem .7rem .75rem}.intel-case-body>p{font-size:.7rem;line-height:1.5;color:#94a3b8;white-space:pre-wrap}.intel-case-body blockquote{margin-top:.5rem;border-left:2px solid #22d3ee;background:rgba(8,145,178,.06);padding:.5rem}.intel-case-body blockquote b{font-size:.6rem;color:#67e8f9}.intel-case-body blockquote p{margin-top:.2rem;font-size:.68rem;color:#cbd5e1}.intel-case-body code{display:block;overflow-wrap:anywhere;padding:.5rem;border-radius:5px;background:rgba(2,6,23,.62);font-size:.62rem;color:#94a3b8}.intel-case-actions{display:grid;gap:.55rem;margin-top:.65rem;padding-top:.65rem;border-top:1px solid rgba(100,116,139,.2)}.intel-badge{display:inline-flex;align-items:center;width:max-content;margin-right:.35rem;border:1px solid rgba(100,116,139,.35);border-radius:99px;padding:.16rem .4rem;font-size:.56rem;font-weight:800;color:#94a3b8}.badge-critical,.badge-major-outage,.badge-blocked,.badge-rejected,.badge-degraded{border-color:rgba(244,63,94,.35);color:#fda4af}.badge-warning,.badge-review,.badge-pending,.badge-partial-outage,.badge-maintenance,.badge-watch{border-color:rgba(245,158,11,.35);color:#fcd34d}.badge-operational,.badge-healthy,.badge-meeting,.badge-approved,.badge-passed,.badge-signed,.badge-resolved,.badge-used{border-color:rgba(16,185,129,.35);color:#6ee7b7}"
                + ".intel-resource{padding:.6rem;border:1px solid rgba(100,116,139,.22);border-radius:6px}.intel-resource>div:first-child{display:flex;align-items:center;justify-content:space-between;gap:.5rem}.intel-resource strong{font-size:.72rem;color:#e2e8f0}.intel-resource .intel-progress{margin-top:.5rem}.intel-resource>small{display:block;margin-top:.35rem;font-size:.6rem;color:#64748b}.intel-storage-summary{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.45rem}.intel-storage-summary>span{padding:.65rem;border:1px solid rgba(100,116,139,.22);border-radius:6px}.intel-storage-summary small,.intel-storage-summary strong{display:block}.intel-storage-summary small{font-size:.6rem;color:#64748b}.intel-storage-summary strong{margin-top:.18rem;font-size:.85rem;color:#e2e8f0}.intel-war-timeline{display:grid;gap:.35rem;margin-top:.65rem}.intel-war-timeline>div{display:grid;grid-template-columns:150px 1fr 115px;gap:.5rem;border-top:1px solid rgba(100,116,139,.16);padding-top:.4rem;font-size:.63rem}.intel-war-timeline b{color:#cbd5e1}.intel-war-timeline span{color:#94a3b8}.intel-war-timeline small{color:#64748b}.intel-public-overall{display:flex;align-items:center;gap:.6rem;border-left:3px solid #64748b;padding:.65rem .75rem;background:rgba(100,116,139,.08);border-radius:5px}.intel-public-overall>i{width:9px;height:9px;border-radius:50%;background:#64748b}.intel-public-overall span{flex:1}.intel-public-overall small,.intel-public-overall strong{display:block}.intel-public-overall small{font-size:.59rem;color:#64748b}.intel-public-overall strong{font-size:.8rem;color:#e2e8f0}.intel-public-overall a{font-size:.68rem;font-weight:800;color:#67e8f9}"
                + "@keyframes intelCardIn{from{opacity:.86;transform:translateY(4px) scale(.998)}to{opacity:1;transform:none}}@keyframes intelPulse{0%{box-shadow:0 0 0 0 rgba(52,211,153,.28)}70%{box-shadow:0 0 0 8px transparent}100%{box-shadow:0 0 0 0 transparent}}@keyframes intelSpin{to{transform:rotate(1turn)}}@keyframes intelGrow{from{transform:scaleX(0)}}"
                + "@media(max-width:1050px){.intel-metrics{grid-template-columns:repeat(3,minmax(0,1fr))}.intel-hero{align-items:flex-start;flex-direction:column}.intel-hero-actions{justify-content:flex-start;width:100%}}"
                + "@media(max-width:760px){.intel-main{padding:.75rem!important}.intel-grid{grid-template-columns:1fr}.intel-card-wide{grid-column:auto}.intel-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.intel-split{grid-template-columns:1fr}.intel-form-grid,.intel-form-grid.compact{grid-template-columns:1fr}.intel-field-wide,.intel-form-grid>.intel-button,.intel-form-grid>.intel-check-grid{grid-column:auto}.intel-check-grid{grid-template-columns:1fr}.intel-inline-form{align-items:stretch}.intel-inline-form>.intel-button{width:100%}.intel-search{flex-wrap:wrap}.intel-search input{width:calc(100% - 42px)!important}.intel-search button{width:100%}.intel-hero-actions{display:grid;grid-template-columns:1fr 1fr}.intel-server-select{grid-column:1/-1}.intel-server-select select{width:100%}.intel-live{justify-content:center}.intel-metric:nth-child(3){grid-column:1/-1}.intel-war-timeline{grid-template-columns:1fr}.intel-war-timeline>div{grid-template-columns:1fr}.intel-card>header,.intel-card-body{padding:.8rem}}"
                + "@media(prefers-reduced-motion:reduce){.intel-card,.intel-live i,.intel-progress i{animation:none!important}.intel-tab,.intel-button{transition:none!important}}"
                + "</style>";
    }

    private static String script() {
        return "<script>(()=>{document.querySelectorAll('[data-intel-form]').forEach(form=>{if(form.dataset.intelReady)return;form.dataset.intelReady='1';form.addEventListener('submit',()=>{form.classList.add('is-submitting');form.querySelectorAll('button').forEach(button=>button.setAttribute('aria-busy','true'));},{once:true});});})();</script>";
    }

    private static String publicStyles() {
        return "<style>:root{color-scheme:dark;font-family:Inter,ui-sans-serif,system-ui,sans-serif;background:#050b14;color:#e2e8f0}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% -20%,#123047 0,#07111e 42%,#050b14 75%);min-height:100vh}main{width:min(920px,calc(100% - 28px));margin:auto;padding:24px 0 40px}header{display:flex;align-items:center;justify-content:space-between;margin-bottom:42px}.brand{display:flex;align-items:center;gap:10px}.brand>span{display:grid;place-items:center;width:30px;height:30px;border-radius:7px;background:#22d3ee;color:#06202a;font-weight:900}.brand strong{font-size:14px}header select{border:1px solid #26364b;background:#0b1726;color:#cbd5e1;border-radius:7px;padding:9px 28px 9px 10px}.hero{display:flex;align-items:center;gap:20px;border:1px solid #24354b;background:rgba(11,23,38,.72);border-radius:8px;padding:26px}.hero p{margin:0 0 5px;color:#64748b;font-size:12px;text-transform:uppercase;letter-spacing:.08em}.hero h1{margin:0;font-size:clamp(25px,5vw,40px);letter-spacing:0}.hero small{display:block;margin-top:7px;color:#64748b}.pulse{display:grid;place-items:center;width:58px;height:58px;border-radius:50%;background:rgba(52,211,153,.1)}.pulse i{width:17px;height:17px;border-radius:50%;background:#34d399;box-shadow:0 0 0 0 rgba(52,211,153,.35);animation:pulse 2.4s infinite}.status-degraded .pulse,.status-partial-outage .pulse,.status-maintenance .pulse{background:rgba(251,191,36,.1)}.status-degraded .pulse i,.status-partial-outage .pulse i,.status-maintenance .pulse i{background:#fbbf24}.status-major-outage .pulse{background:rgba(251,113,133,.1)}.status-major-outage .pulse i{background:#fb7185}.layout{display:grid;grid-template-columns:1fr 1fr;gap:34px;margin-top:34px}.section-title{margin:0 0 12px;color:#94a3b8;font-size:12px;text-transform:uppercase;letter-spacing:.08em}.components{list-style:none;margin:0;padding:0;border:1px solid #1e2c3f;border-radius:8px;overflow:hidden}.components li{display:grid;grid-template-columns:10px 1fr auto;align-items:center;gap:11px;padding:14px;border-top:1px solid #1e2c3f}.components li:first-child{border-top:0}.components strong,.components small{display:block}.components strong{font-size:13px}.components small{margin-top:3px;color:#64748b;font-size:11px}.components b{color:#94a3b8;font-size:11px}.status-dot{width:7px;height:7px;border-radius:50%;background:#34d399}.status-dot.status-degraded,.status-dot.status-partial-outage,.status-dot.status-maintenance{background:#fbbf24}.status-dot.status-major-outage{background:#fb7185}.incidents{display:grid;gap:10px}.incidents article{border:1px solid #1e2c3f;border-radius:8px;padding:14px;background:rgba(11,23,38,.42)}.incidents article>div{display:flex;align-items:center;justify-content:space-between}.incident-severity{border:1px solid #334155;border-radius:99px;padding:3px 7px;color:#94a3b8;font-size:9px;font-weight:800;text-transform:uppercase}.severity-critical{border-color:#7f1d1d;color:#fda4af}.incidents time,.incidents small{font-size:10px;color:#64748b}.incidents h2{margin:10px 0 5px;font-size:14px}.incidents p{margin:0 0 9px;color:#94a3b8;font-size:12px;line-height:1.5}.empty{display:grid;place-items:center;min-height:92px;border:1px dashed #26364b;border-radius:8px;color:#64748b;font-size:12px}footer{text-align:center;margin-top:38px;color:#475569;font-size:10px}@keyframes pulse{70%{box-shadow:0 0 0 11px transparent}}@media(max-width:680px){header{margin-bottom:24px}.layout{grid-template-columns:1fr}.hero{padding:19px}.components li{grid-template-columns:10px 1fr}.components b{grid-column:2}}@media(prefers-reduced-motion:reduce){.pulse i{animation:none}}</style>";
    }

    public record ServerOption(long id, String name, boolean online) {
    }

    public record RuntimeMetrics(boolean online, double tps, double mspt, long memoryMb, int players) {
        public static RuntimeMetrics offline() {
            return new RuntimeMetrics(false, 0.0, 0.0, 0L, 0);
        }
    }
}
