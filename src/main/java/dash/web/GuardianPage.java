package dash.web;

public class GuardianPage {
    public static String render(String msg) {
        boolean canExport = HtmlTemplate.can("dash.web.guardian.export");
        boolean canImport = HtmlTemplate.can("dash.web.guardian.import");
        boolean canRollback = HtmlTemplate.can("dash.web.guardian.rollback")
                || HtmlTemplate.can("dash.web.guardian.restore")
                || HtmlTemplate.can("dash.web.guardian.manage");
        boolean canCases = HtmlTemplate.can("dash.web.guardian.cases")
                || HtmlTemplate.can("dash.web.guardian.manage");
        boolean canFilters = HtmlTemplate.can("dash.web.guardian.filters")
                || HtmlTemplate.can("dash.web.guardian.manage");
        boolean canNotes = HtmlTemplate.can("dash.web.guardian.notes")
                || HtmlTemplate.can("dash.web.guardian.manage");
        boolean canPurge = HtmlTemplate.can("dash.web.guardian.purge")
                || HtmlTemplate.can("dash.web.guardian.manage");
        boolean canManage = HtmlTemplate.can("dash.web.guardian.manage");
        int selectionButtonCount = 2 + (canCases ? 1 : 0) + (canNotes ? 1 : 0);

        String messageHtml = msg == null || msg.isBlank() ? ""
                : "<div class='mb-4 rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>"
                + escape(msg) + "</div>";

        String exportButtons = canExport
                ? "<a id='guardian-export-blocks' href='/api/guardian/export/blocks' class='dash-btn-subtle'><span class='material-symbols-outlined text-[17px]'>download</span>Blocks</a>"
                + "<a id='guardian-export-containers' href='/api/guardian/export/containers' class='dash-btn-subtle'><span class='material-symbols-outlined text-[17px]'>download</span>Containers</a>"
                : "";

        String savedFilters = canFilters
                ? card("Saved Filters", "bookmark",
                        "<div id='guardian-saved-filters' class='space-y-2 text-sm'></div>"
                                + "<form id='guardian-filter-save' class='mt-3 flex gap-2'>"
                                + "<input name='name' placeholder='Filter name' class='guardian-compact-input flex-1'>"
                                + "<button class='dash-icon-btn' title='Save current filter'><span class='material-symbols-outlined text-[18px]'>save</span></button>"
                                + "</form>")
                : "";

        String casePanel = canCases
                ? card("Cases", "cases",
                        "<div id='guardian-cases' class='space-y-2 text-sm max-h-[280px] overflow-y-auto console-scrollbar pr-1'></div>"
                                + "<div id='guardian-case-actions' class='mt-3 hidden rounded-xl border border-white/10 bg-black/20 p-3'>"
                                + "<p class='mb-2 text-[11px] uppercase tracking-wider text-slate-500'>Selected Case</p>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + "<button type='button' data-case-status='INVESTIGATING' class='dash-btn-subtle justify-center'>Investigate</button>"
                                + "<button type='button' data-case-status='RESOLVED' class='dash-btn-subtle justify-center'>Resolve</button>"
                                + "<button type='button' data-case-status='FALSE_ALARM' class='dash-btn-subtle justify-center'>False Alarm</button>"
                                + "<button type='button' data-case-status='OPEN' class='dash-btn-subtle justify-center'>Reopen</button>"
                                + "<a id='guardian-case-bundle' href='#' target='_blank' class='dash-btn-subtle justify-center col-span-2'><span class='material-symbols-outlined text-[17px]'>ios_share</span>Evidence Bundle</a>"
                                + "</div></div>"
                                + "<form id='guardian-case-form' class='mt-4 space-y-3'>"
                                + field("Title", "<input name='title' placeholder='Chest theft near spawn' required>")
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Priority", "<select name='priority'><option>NORMAL</option><option>LOW</option><option>HIGH</option><option>URGENT</option></select>")
                                + field("Player", "<input name='player' id='case-player' placeholder='Optional'>")
                                + "</div>"
                                + "<div class='grid grid-cols-4 gap-2'>"
                                + field("World", "<input name='world' id='case-world' placeholder='world'>")
                                + field("X", "<input name='x' id='case-x' type='number'>")
                                + field("Y", "<input name='y' id='case-y' type='number'>")
                                + field("Z", "<input name='z' id='case-z' type='number'>")
                                + "</div>"
                                + field("Notes", "<textarea name='notes' rows='3' placeholder='Internal notes'></textarea>")
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>add_task</span>Create Case</button>"
                                + "</form>")
                : "";

        String notesPanel = canNotes
                ? card("Player Watchlist", "flag",
                        "<div id='guardian-player-notes' class='space-y-2 text-sm max-h-[220px] overflow-y-auto console-scrollbar pr-1'></div>"
                                + "<form id='guardian-note-form' class='mt-4 space-y-3'>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Player", "<input name='player' id='note-player' placeholder='Player' required>")
                                + field("Severity", "<select name='severity' id='note-severity'><option>WATCH</option><option>ALERT</option><option>INFO</option><option>TRUSTED</option></select>")
                                + "</div>"
                                + field("Notes", "<textarea name='notes' id='note-notes' rows='3' placeholder='Private moderation note'></textarea>")
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>save</span>Save</button>"
                                + "<button id='guardian-note-delete' type='button' class='dash-btn-subtle justify-center'><span class='material-symbols-outlined text-[18px]'>delete</span>Delete</button>"
                                + "</div></form>")
                : "";

        String actionPanel = canRollback
                ? card("Rollback Preview", "restore",
                        "<form id='guardian-action-form' class='space-y-3'>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Mode", "<select name='mode'><option value='rollback'>Rollback</option><option value='restore'>Restore</option></select>")
                                + field("Scope", "<select name='scope'><option value='both'>Blocks + Containers</option><option value='blocks'>Blocks</option><option value='containers'>Containers</option></select>")
                                + "</div>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Hours", "<input name='hours' type='number' min='1' max='2160' value='24'>")
                                + field("Limit", "<input name='limit' type='number' min='1' max='10000' value='1000'>")
                                + "</div>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Player", "<input name='player' id='action-player' placeholder='Optional'>")
                                + field("World", "<input name='world' id='action-world' placeholder='Optional'>")
                                + "</div>"
                                + "<div class='grid grid-cols-4 gap-2'>"
                                + field("X", "<input name='x' id='action-x' type='number'>")
                                + field("Y", "<input name='y' id='action-y' type='number'>")
                                + field("Z", "<input name='z' id='action-z' type='number'>")
                                + field("Radius", "<input name='radius' type='number' min='0' value='0'>")
                                + "</div>"
                                + field("Action", "<select name='action'><option value=''>All</option><option value='break'>Break / Remove</option><option value='place'>Place / Add</option></select>")
                                + field("Include", "<input name='include' placeholder='stone,chest'>")
                                + field("Exclude", "<input name='exclude' placeholder='bedrock,barrier'>")
                                + "<label class='flex items-center gap-2 text-xs font-semibold text-slate-300'><input name='preview' type='checkbox' checked class='rounded border-glass-border bg-slate-900 text-primary'>Preview first</label>"
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>play_arrow</span>Run</button>"
                                + "<div id='guardian-action-result' class='hidden rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-xs text-slate-300'></div>"
                                + "</form>")
                : "";

        String purgePanel = canPurge
                ? card("Purge Logs", "delete_sweep",
                        "<form id='guardian-purge-form' class='space-y-3'>"
                                + field("Older Than Hours", "<input name='hours' type='number' min='1' value='720'>")
                                + field("World", "<input name='world' placeholder='Optional world'>")
                                + field("Include", "<input name='include' placeholder='Optional materials'>")
                                + "<button class='dash-danger-btn w-full'><span class='material-symbols-outlined text-[18px]'>delete</span>Purge</button>"
                                + "</form>")
                : "";

        String retentionPanel = canPurge
                ? card("Retention Policy", "policy",
                        "<div id='guardian-retention-status' class='mb-3 text-xs text-slate-500'>Loading policy...</div>"
                                + "<form id='guardian-retention-form' class='space-y-3'>"
                                + field("Keep Logs Days", "<input name='logDays' type='number' min='1' max='3650' value='90'>")
                                + "<label class='flex items-center gap-2 text-xs font-semibold text-slate-300'><input name='keepCases' type='checkbox' checked class='rounded border-glass-border bg-slate-900 text-primary'>Keep cases and evidence</label>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>save</span>Save</button>"
                                + "<button id='guardian-retention-apply' type='button' class='dash-danger-btn w-full'><span class='material-symbols-outlined text-[18px]'>auto_delete</span>Apply</button>"
                                + "</div></form>")
                : "";

        String regionPanel = canManage
                ? card("Protected Regions", "shield_lock",
                        "<div id='guardian-region-hits' class='mb-3 space-y-2 text-sm max-h-[160px] overflow-y-auto console-scrollbar pr-1'></div>"
                                + "<div id='guardian-regions' class='mb-3 space-y-2 text-sm max-h-[160px] overflow-y-auto console-scrollbar pr-1'></div>"
                                + "<form id='guardian-region-form' class='space-y-3'>"
                                + field("Name", "<input name='name' placeholder='Spawn / Shop / Base' required>")
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("World", "<input name='world' id='region-world' placeholder='world' required>")
                                + field("Severity", "<select name='severity'><option>WATCH</option><option>ALERT</option><option>CRITICAL</option><option>INFO</option></select>")
                                + "</div>"
                                + "<div class='grid grid-cols-3 gap-2'>"
                                + field("X1", "<input name='x1' id='region-x1' type='number' required>")
                                + field("Y1", "<input name='y1' id='region-y1' type='number' required>")
                                + field("Z1", "<input name='z1' id='region-z1' type='number' required>")
                                + "</div>"
                                + "<div class='grid grid-cols-3 gap-2'>"
                                + field("X2", "<input name='x2' id='region-x2' type='number' required>")
                                + field("Y2", "<input name='y2' id='region-y2' type='number' required>")
                                + field("Z2", "<input name='z2' id='region-z2' type='number' required>")
                                + "</div>"
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>add_location_alt</span>Save Region</button>"
                                + "</form>")
                : "";

        String alertRulePanel = canManage
                ? card("Alert Rules", "notification_important",
                        "<div id='guardian-alert-hits' class='mb-3 space-y-2 text-sm max-h-[160px] overflow-y-auto console-scrollbar pr-1'></div>"
                                + "<div id='guardian-alert-rules' class='mb-3 space-y-2 text-sm max-h-[180px] overflow-y-auto console-scrollbar pr-1'></div>"
                                + "<form id='guardian-alert-rule-form' class='space-y-3'>"
                                + field("Name", "<input name='name' placeholder='Diamond rush / chest sweep' required>")
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Window Seconds", "<input name='windowSeconds' type='number' min='60' value='600'>")
                                + field("Min Actions", "<input name='minActions' type='number' min='1' value='25'>")
                                + "</div>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Action", "<select name='action'><option value=''>Any</option><option value='break'>Break</option><option value='place'>Place</option><option value='remove'>Remove</option><option value='add'>Add</option></select>")
                                + field("Priority", "<select name='priority'><option>HIGH</option><option>URGENT</option><option>NORMAL</option><option>LOW</option></select>")
                                + "</div>"
                                + field("Material", "<input name='material' placeholder='Optional material'>")
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + "<label class='flex items-center gap-2 text-xs font-semibold text-slate-300'><input name='enabled' type='checkbox' checked class='rounded border-glass-border bg-slate-900 text-primary'>Enabled</label>"
                                + "<label class='flex items-center gap-2 text-xs font-semibold text-slate-300'><input name='autoCase' type='checkbox' checked class='rounded border-glass-border bg-slate-900 text-primary'>Auto case</label>"
                                + "</div>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>rule</span>Save Rule</button>"
                                + "<button id='guardian-alert-evaluate' type='button' class='dash-btn-subtle justify-center'><span class='material-symbols-outlined text-[18px]'>task_alt</span>Evaluate</button>"
                                + "</div></form>")
                : "";

        String coreProtectPanel = canImport
                ? card("CoreProtect Import", "database",
                        "<div class='mb-3 flex items-center justify-between text-xs text-slate-500'><span>Optional source</span><span id='coreprotect-status'>Checking</span></div>"
                                + "<form id='coreprotect-import' class='space-y-3'>"
                                + "<div class='grid grid-cols-2 gap-2'>"
                                + field("Hours", "<input name='hours' type='number' min='1' max='2160' value='24'>")
                                + field("Limit", "<input name='limit' type='number' min='1' max='10000' value='2000'>")
                                + "</div>"
                                + field("Player", "<input name='player' placeholder='Any player'>")
                                + "<button class='dash-primary-btn w-full'><span class='material-symbols-outlined text-[18px]'>sync</span>Import</button>"
                                + "</form>")
                : "";

        String content = HtmlTemplate.statsHeader()
                + "<main class='guardian-main p-4 sm:p-6 flex-1 w-full'>"
                + messageHtml
                + "<section class='mb-4 rounded-lg bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex flex-col lg:flex-row lg:items-center justify-between gap-3'>"
                + "<div class='flex items-center gap-3 min-w-0'><span class='material-symbols-outlined text-primary text-[28px]'>shield</span>"
                + "<div class='min-w-0'><h1 class='text-2xl font-display font-semibold text-white tracking-tight'>Guardian</h1>"
                + "<p class='text-sm text-slate-500'>Search, inspect, case-build and restore without requiring CoreProtect.</p></div></div>"
                + "<div class='flex flex-wrap items-center gap-2'>" + exportButtons + "</div>"
                + "</div>"
                + "<div class='mt-4 grid grid-cols-2 md:grid-cols-5 border border-white/5 rounded-2xl overflow-hidden bg-slate-950/20'>"
                + metric("Broken", "guardian-broken")
                + metric("Placed", "guardian-placed")
                + metric("Removed", "guardian-removed")
                + metric("Added", "guardian-added")
                + metric("Players", "guardian-players")
                + "</div></section>"
                + "<div class='mb-4 grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_320px] gap-4 items-start'>"
                + card("Search", "manage_search",
                        "<form id='guardian-filters' class='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 items-end'>"
                                + "<div class='sm:col-span-2'>" + field("Global Search", "<input name='q' placeholder='player, world or material'>") + "</div>"
                                + field("Player", "<input name='player' placeholder='Exact or partial player'>")
                                + field("World", "<select id='guardian-world' name='world'><option value=''>All worlds</option></select>")
                                + field("Hours", "<input name='hours' type='number' min='1' max='2160' value='24'>")
                                + field("Action", "<select name='action'><option value=''>All</option><option value='break'>Break / Remove</option><option value='place'>Place / Add</option></select>")
                                + "<button class='dash-primary-btn w-full sm:col-span-2'><span class='material-symbols-outlined text-[18px]'>search</span>Search</button>"
                                + "<details class='sm:col-span-2 lg:col-span-4 rounded-xl border border-white/10 bg-black/20 px-3 py-2'>"
                                + "<summary class='cursor-pointer text-xs font-bold text-slate-300'>Coordinates & material filters</summary>"
                                + "<div class='mt-3 grid grid-cols-2 lg:grid-cols-6 gap-2'>"
                                + field("X", "<input name='x' type='number'>")
                                + field("Y", "<input name='y' type='number'>")
                                + field("Z", "<input name='z' type='number'>")
                                + field("Radius", "<input name='radius' type='number' min='0' value='5'>")
                                + "<div class='lg:col-span-1'>" + field("Include", "<input name='include' placeholder='diamond_ore,chest'>") + "</div>"
                                + "<div class='lg:col-span-1'>" + field("Exclude", "<input name='exclude' placeholder='stone,dirt'>") + "</div>"
                                + "</div></details>"
                                + "</form>")
                + savedFilters
                + "</div>"
                + "<section class='rounded-lg bg-glass-surface border border-glass-border overflow-hidden'>"
                + "<div class='flex flex-col lg:flex-row lg:items-center justify-between gap-3 px-5 py-4 border-b border-white/5'>"
                + "<div><h2 class='text-lg font-display font-semibold text-white'>Activity</h2><p id='guardian-status-line' class='text-xs text-slate-500'>Loading Guardian data...</p></div>"
                + "<div class='flex w-full flex-col sm:flex-row sm:items-center lg:w-auto gap-2'>"
                + "<div class='grid w-full grid-cols-3 p-1 bg-slate-950/40 rounded-2xl border border-glass-border lg:w-[480px]'>"
                + tabButton("timeline", "Timeline", true)
                + tabButton("blocks", "Blocks", false)
                + tabButton("containers", "Containers", false)
                + "</div>"
                + "<div class='inline-flex items-center gap-1 rounded-2xl border border-glass-border bg-slate-950/30 p-1'>"
                + "<button id='guardian-page-prev' type='button' data-guardian-page='-1' class='guardian-page-btn dash-icon-btn justify-center px-2' disabled><span class='material-symbols-outlined text-[18px]'>chevron_left</span></button>"
                + "<span id='guardian-page-label' class='min-w-[160px] px-2 text-center text-xs font-bold text-slate-300'>Page 1 / 1</span>"
                + "<button id='guardian-page-next' type='button' data-guardian-page='1' class='guardian-page-btn dash-icon-btn justify-center px-2' disabled><span class='material-symbols-outlined text-[18px]'>chevron_right</span></button>"
                + "</div></div></div>"
                + table("guardian-timeline-view", "guardian-timeline-table",
                        "<th class='px-4 py-3'>Time</th><th class='px-4 py-3'>Player</th><th class='px-4 py-3'>Type</th><th class='px-4 py-3'>Target</th><th class='px-4 py-3'>Location</th><th class='px-4 py-3'>Source</th>")
                + table("guardian-block-view", "guardian-block-table",
                        "<th class='px-4 py-3'>Time</th><th class='px-4 py-3'>Player</th><th class='px-4 py-3'>Action</th><th class='px-4 py-3'>Block</th><th class='px-4 py-3'>Location</th><th class='px-4 py-3'>Source</th>",
                        "display:none")
                + table("guardian-container-view", "guardian-container-table",
                        "<th class='px-4 py-3'>Time</th><th class='px-4 py-3'>Player</th><th class='px-4 py-3'>Action</th><th class='px-4 py-3'>Item</th><th class='px-4 py-3'>Location</th><th class='px-4 py-3'>Source</th>",
                        "display:none")
                + "</section>"
                + "<section class='mt-4'>"
                + "<div class='grid grid-cols-2 sm:grid-cols-5 gap-1 rounded-lg border border-glass-border bg-slate-950/30 p-1'>"
                + workspaceTab("overview", "Investigate", true)
                + workspaceTab("cases", "Cases", false)
                + workspaceTab("rules", "Protection", false)
                + workspaceTab("actions", "Recovery", false)
                + workspaceTab("data", "Retention", false)
                + "</div>"
                + "<div data-guardian-workspace='overview' class='mt-4 grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4 items-start'>"
                + card("Status", "storage", "<div id='guardian-status' class='space-y-2 text-sm text-slate-400'></div>")
                + card("Insights", "query_stats",
                        "<div id='guardian-insights' class='space-y-3 text-sm text-slate-400'></div>")
                + card("Case Inbox", "inbox",
                        "<div id='guardian-inbox' class='space-y-2 text-sm max-h-[220px] overflow-y-auto console-scrollbar pr-1'></div>")
                + card("Admin Activity", "manage_history",
                        "<div id='guardian-activity' class='space-y-2 text-sm max-h-[190px] overflow-y-auto console-scrollbar pr-1'></div>")
                + card("Incidents", "warning",
                        "<div id='guardian-incidents' class='space-y-2 text-sm max-h-[220px] overflow-y-auto console-scrollbar pr-1'></div>")
                + card("Suspicion Scores", "speed",
                        "<div id='guardian-scores' class='space-y-2 text-sm max-h-[220px] overflow-y-auto console-scrollbar pr-1'></div>")
                + card("Selection", "my_location",
                        "<div id='guardian-selection' class='text-sm text-slate-500'>Select an event to inspect it.</div>"
                                + "<div class='mt-3 grid grid-cols-" + selectionButtonCount + " gap-2'>"
                                + "<button id='guardian-copy-tp' type='button' class='dash-btn-subtle justify-center' disabled><span class='material-symbols-outlined text-[17px]'>content_copy</span>TP</button>"
                                + "<button id='guardian-fill-action' type='button' class='dash-btn-subtle justify-center' disabled><span class='material-symbols-outlined text-[17px]'>input</span>Use</button>"
                                + (canCases ? "<button id='guardian-attach-evidence' type='button' class='dash-btn-subtle justify-center' disabled><span class='material-symbols-outlined text-[17px]'>attach_file</span>Case</button>" : "")
                                + (canNotes ? "<button id='guardian-flag-player' type='button' class='dash-btn-subtle justify-center' disabled><span class='material-symbols-outlined text-[17px]'>flag</span>Note</button>" : "")
                                + "</div>")
                + card("Timeline Replay", "movie",
                        "<div id='guardian-replay' class='space-y-2 text-sm max-h-[220px] overflow-y-auto console-scrollbar pr-1'>Select an event to replay nearby history.</div>")
                + card("Container Restore", "inventory_2",
                        "<div id='guardian-container-restore' class='space-y-2 text-sm max-h-[220px] overflow-y-auto console-scrollbar pr-1'>Select a container event to plan item restore.</div>")
                + card("Quick Tools", "bolt",
                        "<div class='grid grid-cols-2 gap-2'>"
                                + "<button type='button' data-guardian-tool='near' class='dash-btn-subtle justify-center'><span class='material-symbols-outlined text-[17px]'>radar</span>Near</button>"
                                + "<button type='button' data-guardian-tool='lookup' class='dash-btn-subtle justify-center'><span class='material-symbols-outlined text-[17px]'>manage_search</span>Lookup</button>"
                                + "<button type='button' data-guardian-tool='has-placed' class='dash-btn-subtle justify-center'><span class='material-symbols-outlined text-[17px]'>add_box</span>Placed?</button>"
                                + "<button type='button' data-guardian-tool='has-removed' class='dash-btn-subtle justify-center'><span class='material-symbols-outlined text-[17px]'>indeterminate_check_box</span>Removed?</button>"
                                + "</div>"
                                + "<div id='guardian-tool-result' class='mt-3 rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-xs text-slate-400'>Select an event, then run a tool.</div>")
                + "</div>"
                + "<div data-guardian-workspace='cases' class='hidden mt-4 grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4 items-start'>"
                + casePanel
                + notesPanel
                + "</div>"
                + "<div data-guardian-workspace='actions' class='hidden mt-4 grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4 items-start'>"
                + actionPanel
                + regionPanel
                + purgePanel
                + "</div>"
                + "<div data-guardian-workspace='rules' class='hidden mt-4 grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4 items-start'>"
                + alertRulePanel
                + "</div>"
                + "<div data-guardian-workspace='data' class='hidden mt-4 grid grid-cols-1 lg:grid-cols-2 2xl:grid-cols-3 gap-4 items-start'>"
                + retentionPanel + coreProtectPanel + "</div>"
                + "</section></main>"
                + HtmlTemplate.statsScript()
                + script(canCases);
        return HtmlTemplate.page("Guardian", "/guardian", content);
    }

    private static String card(String title, String icon, String body) {
        return "<section class='guardian-card rounded-lg bg-glass-surface border border-glass-border p-5'>"
                + "<div class='flex items-center gap-2 mb-4'><span class='material-symbols-outlined text-primary text-[20px]'>"
                + icon + "</span><h2 class='text-base font-bold text-white'>" + title + "</h2></div>"
                + body + "</section>";
    }

    private static String field(String label, String control) {
        return "<label class='block text-xs font-medium text-slate-400 uppercase tracking-wider'>"
                + label + "<span class='mt-2 block guardian-field'>" + control + "</span></label>";
    }

    private static String metric(String label, String id) {
        return "<div class='px-4 py-3 border-r border-white/5 last:border-r-0'>"
                + "<p class='text-[11px] uppercase tracking-wider text-slate-500'>" + label + "</p>"
                + "<p id='" + id + "' class='mt-1 text-xl font-bold text-white'>0</p></div>";
    }

    private static String tabButton(String tab, String label, boolean active) {
        return "<button type='button' data-guardian-tab='" + tab + "' class='guardian-tab w-full justify-center px-4 py-2 rounded-xl text-xs font-bold transition-all "
                + (active ? "bg-primary text-black shadow-lg" : "text-slate-400 hover:text-white hover:bg-white/5")
                + "'>" + label + "</button>";
    }

    private static String workspaceTab(String tab, String label, boolean active) {
        return "<button type='button' data-guardian-workspace-tab='" + tab
                + "' class='guardian-workspace-tab w-full rounded-md px-3 py-2.5 text-xs font-bold transition-all "
                + (active ? "bg-primary text-black shadow-lg" : "text-slate-400 hover:bg-white/5 hover:text-white")
                + "'>" + label + "</button>";
    }

    private static String table(String id, String tbodyId, String headers) {
        return table(id, tbodyId, headers, "");
    }

    private static String table(String id, String tbodyId, String headers, String style) {
        return "<div id='" + id + "' class='w-full max-h-[360px] overflow-auto console-scrollbar' style='" + style + "'>"
                + "<table class='w-full min-w-[980px] text-left'>"
                + "<thead class='sticky top-0 z-10 bg-slate-950/90 backdrop-blur'><tr class='text-left font-display font-semibold text-[13px] text-slate-300'>"
                + headers + "</tr></thead>"
                + "<tbody id='" + tbodyId + "'></tbody></table></div>";
    }

    private static String script(boolean canCases) {
        return """
        <style>
        .guardian-field input,.guardian-field select,.guardian-field textarea,.guardian-compact-input{width:100%;background:rgba(2,6,23,.4);border:1px solid rgba(255,255,255,.1);border-radius:.375rem;padding:.625rem .75rem;color:white;font-size:.875rem;outline:none}
        .guardian-field input::placeholder,.guardian-field textarea::placeholder,.guardian-compact-input::placeholder{color:#64748b}
        .guardian-field input:focus,.guardian-field select:focus,.guardian-field textarea:focus,.guardian-compact-input:focus{border-color:#22d3ee}
        .dash-primary-btn{display:inline-flex;align-items:center;justify-content:center;gap:.5rem;border-radius:.75rem;background:rgba(34,211,238,.18);border:1px solid rgba(34,211,238,.3);padding:.65rem .9rem;font-size:.8rem;font-weight:800;color:#67e8f9;transition:.2s}
        .dash-primary-btn:hover{background:#22d3ee;color:#06121a}
        .dash-danger-btn{display:inline-flex;align-items:center;justify-content:center;gap:.5rem;border-radius:.75rem;background:rgba(244,63,94,.14);border:1px solid rgba(244,63,94,.28);padding:.65rem .9rem;font-size:.8rem;font-weight:800;color:#fda4af;transition:.2s}
        .dash-danger-btn:hover{background:#f43f5e;color:white}
        .dash-btn-subtle,.dash-icon-btn{display:inline-flex;align-items:center;gap:.45rem;border-radius:.75rem;border:1px solid rgba(255,255,255,.1);background:rgba(2,6,23,.35);padding:.55rem .75rem;font-size:.75rem;font-weight:800;color:#cbd5e1;transition:.2s}
        .dash-btn-subtle:hover,.dash-icon-btn:hover{border-color:rgba(34,211,238,.45);color:#67e8f9}
        .dash-btn-subtle:disabled{opacity:.45;cursor:not-allowed}
        .guardian-page-btn:disabled{opacity:.35;cursor:not-allowed}
        @media (prefers-reduced-motion:no-preference){.guardian-row{animation:guardian-row-in .18s cubic-bezier(.2,.8,.2,1) both}@keyframes guardian-row-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}}
        </style>
        <script>
        (function(){
        if(window.dashPageAbortController){try{window.dashPageAbortController.abort();}catch(_){}}
        const guardianAbortController=new AbortController();
        window.dashPageAbortController=guardianAbortController;
        const guardianEventOptions={signal:guardianAbortController.signal};
        const esc=s=>(s??'').toString().replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
        const fmt=n=>Number(n||0).toLocaleString();
        const asArray=value=>Array.isArray(value)?value:[];
        const form=document.getElementById('guardian-filters');
        let activeGuardianTab='timeline';
        let selectedEvent=null;
        let selectedCaseId=0;
        let guardianRows={timeline:[],blocks:[],containers:[]};
        let guardianPage={timeline:1,blocks:1,containers:1};
        let guardianLoadSequence=0;
        const guardianPageSize=10;
        function params(){return new URLSearchParams(new FormData(form));}
        function actionFor(type,action){if(!action)return'';if(type==='blocks')return action==='break'?'break':'place';return action==='break'?'remove':'add';}
        function loc(r){return `${esc(r.world)} <span class="text-slate-500">${r.x}, ${r.y}, ${r.z}</span>`;}
        function badge(text,tone){return `<span class="px-2 py-0.5 rounded-lg text-xs font-semibold ${tone}">${esc(text)}</span>`;}
        function activeRows(){return guardianRows[activeGuardianTab]||[];}
        function activeTotalPages(){return Math.max(1,Math.ceil(activeRows().length/guardianPageSize));}
        function pageRows(tab){const rows=guardianRows[tab]||[];const total=Math.max(1,Math.ceil(rows.length/guardianPageSize));const page=Math.min(Math.max(guardianPage[tab]||1,1),total);guardianPage[tab]=page;return rows.slice((page-1)*guardianPageSize,page*guardianPageSize);}
        function renderPagedGuardian(){renderTimeline(pageRows('timeline'));renderBlocks(pageRows('blocks'));renderContainers(pageRows('containers'));updatePager();}
        function updatePager(){const rows=activeRows();const total=activeTotalPages();const page=Math.min(Math.max(guardianPage[activeGuardianTab]||1,1),total);guardianPage[activeGuardianTab]=page;const label=document.getElementById('guardian-page-label');if(label){const start=rows.length?((page-1)*guardianPageSize+1):0;const end=Math.min(rows.length,page*guardianPageSize);label.textContent=`Page ${page} / ${total} - ${fmt(start)}-${fmt(end)} of ${fmt(rows.length)}`;}const prev=document.getElementById('guardian-page-prev');const next=document.getElementById('guardian-page-next');if(prev)prev.disabled=page<=1;if(next)next.disabled=page>=total;}
        async function apiJson(path,q){return fetch(path+(q?('?'+q):''),{credentials:'same-origin',cache:'no-store'}).then(async r=>{const body=await r.json().catch(()=>null);return r.ok?body:null;}).catch(()=>null);}
        async function postForm(path,data){return fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data),credentials:'same-origin',cache:'no-store'}).then(async r=>{const body=await r.json().catch(()=>null);return body&&typeof body==='object'?body:{success:r.ok,error:r.ok?'':'Request failed'};}).catch(()=>({success:false,error:'Request failed'}));}
        async function loadGuardian(){
          const sequence=++guardianLoadSequence;
          const p=params();
          const hours=p.get('hours')||24;
          const base=new URLSearchParams(p);base.set('limit','100');
          const bp=new URLSearchParams(p);bp.set('limit','100');bp.set('action',actionFor('blocks',p.get('action')));
          const cp=new URLSearchParams(p);cp.set('limit','100');cp.set('action',actionFor('containers',p.get('action')));
          const [stats,status,timeline,blocks,containers,cases,filters,notes,insights]=await Promise.all([
            apiJson('/api/guardian/stats'),
            apiJson('/api/guardian/status'),
            apiJson('/api/guardian/timeline/events',base),
            apiJson('/api/guardian/logs/blocks',bp),
            apiJson('/api/guardian/logs/containers',cp),
            apiJson('/api/guardian/cases',new URLSearchParams('limit=20')),
            apiJson('/api/guardian/saved-filters'),
            loadPlayerNotes(p),
            loadInsights(p)
          ]);
          if(sequence!==guardianLoadSequence)return;
          if(stats){setText('guardian-broken',stats.totalBlocksBroken);setText('guardian-placed',stats.totalBlocksPlaced);setText('guardian-removed',stats.totalItemsRemoved);setText('guardian-added',stats.totalItemsAdded);setText('guardian-players',stats.uniquePlayers);}
          guardianRows={timeline:asArray(timeline),blocks:asArray(blocks),containers:asArray(containers)};
          guardianPage={timeline:1,blocks:1,containers:1};
          renderStatus(status);
          renderPagedGuardian();renderCases(asArray(cases));renderFilters(asArray(filters));renderPlayerNotes(asArray(notes));renderInsights(insights);
          loadGuardianPanels(p,sequence);
          updateExports(p);
          const line=document.getElementById('guardian-status-line');
          if(line)line.textContent=`${fmt(guardianRows.timeline.length)} timeline rows, ${fmt(guardianRows.blocks.length)} block rows, ${fmt(guardianRows.containers.length)} container rows`;
        }
        async function loadInsights(p){const hours=p.get('hours')||'24';const since=new URLSearchParams();since.set('hours',hours);const [heat,suspicious,top,types,peaks]=await Promise.all([apiJson('/api/guardian/heatmap',since),apiJson('/api/guardian/suspicious',since),apiJson('/api/guardian/top-players',since),apiJson('/api/guardian/block-types',since),apiJson('/api/guardian/peak-hours',since)]);return {heat:asArray(heat),suspicious:asArray(suspicious),top:asArray(top),types:asArray(types),peaks:asArray(peaks)};}
        async function loadPlayerNotes(p){if(!document.getElementById('guardian-player-notes'))return[];const q=new URLSearchParams();q.set('limit','20');const search=p.get('player')||p.get('q')||'';if(search)q.set('q',search);return asArray(await apiJson('/api/guardian/player-notes',q));}
        async function loadGuardianPanels(p,sequence){const q=new URLSearchParams();q.set('hours',p.get('hours')||'24');q.set('limit','20');const [inbox,incidents,scores,regions,regionHits,rules,ruleHits,retention,activity]=await Promise.all([apiJson('/api/guardian/inbox',q),apiJson('/api/guardian/incidents',q),apiJson('/api/guardian/scores',q),apiJson('/api/guardian/protected-regions'),apiJson('/api/guardian/protected-regions/hits',q),apiJson('/api/guardian/alert-rules'),apiJson('/api/guardian/alert-rules/hits'),apiJson('/api/guardian/retention'),apiJson('/api/guardian/activity')]);if(sequence!==guardianLoadSequence)return;renderInbox(inbox);renderIncidents(asArray(incidents));renderScores(asArray(scores));renderRegions(asArray(regions));renderRegionHits(asArray(regionHits));renderAlertRules(asArray(rules));renderAlertHits(asArray(ruleHits));renderRetention(retention);renderActivity(asArray(activity));}
        function smallList(title,rows,render){const safe=rows||[];return `<div><p class="mb-2 text-[11px] uppercase tracking-wider text-slate-500">${esc(title)}</p><div class="space-y-1">${safe.length?safe.slice(0,4).map(render).join(''):'<p class="text-xs text-slate-600">No data yet.</p>'}</div></div>`;}
        function insightRow(left,right){return `<div class="flex items-center justify-between gap-3 rounded-xl border border-white/10 bg-black/20 px-2.5 py-2"><span class="truncate text-slate-300">${left}</span><span class="shrink-0 font-mono text-slate-500">${right}</span></div>`;}
        function renderInsights(data){const box=document.getElementById('guardian-insights');if(!box)return;if(!data){box.innerHTML='<p>Insights unavailable.</p>';return;}box.innerHTML=smallList('Suspicious',data.suspicious,r=>insightRow(esc(r.player||r.playerName||'Unknown'),fmt(r.totalBroken||r.total||0)))+smallList('Top Players',data.top,r=>insightRow(esc(r.player||r.playerName||'Unknown'),fmt(r.totalActions||r.total||0)))+smallList('Hot Chunks',data.heat,r=>insightRow(`${esc(r.world||'world')} ${r.chunkX}, ${r.chunkZ}`,fmt(r.count)))+smallList('Block Types',data.types,r=>insightRow(esc(r.label||r.blockType||r.type||'Unknown'),fmt(r.count)))+smallList('Peak Hours',data.peaks,r=>insightRow(esc(r.label||r.hour||r.timeSlot||'Unknown'),fmt(r.count)));}
        function renderInbox(data){const box=document.getElementById('guardian-inbox');if(!box)return;if(!data){box.innerHTML='<p class="text-slate-500">Inbox unavailable.</p>';return;}const rows=[];(data.alerts||[]).slice(0,4).forEach(a=>rows.push(`<div class="rounded-xl border border-rose-500/20 bg-rose-500/10 px-3 py-2"><div class="flex justify-between gap-2"><span class="font-semibold text-rose-200 truncate">${esc(a.player)}</span><span class="text-[11px] text-rose-300">${fmt(a.count)}</span></div><p class="text-[11px] text-slate-400 truncate">${esc(a.rule)}</p></div>`));(data.openCases||[]).slice(0,4).forEach(c=>rows.push(`<button type="button" data-case-id="${c.id}" class="guardian-case block w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-left"><div class="flex justify-between gap-2"><span class="font-semibold text-slate-100 truncate">${esc(c.title)}</span><span class="text-[11px] text-slate-500">${esc(c.priority)}</span></div><p class="text-[11px] text-slate-500 truncate">${esc(c.player||'No player')}</p></button>`));box.innerHTML=rows.length?rows.join(''):'<p class="text-slate-500">No open inbox items.</p>';}
        function renderActivity(rows){const box=document.getElementById('guardian-activity');if(!box)return;rows=asArray(rows);if(!rows.length){box.innerHTML='<p class="text-slate-500">No Guardian admin activity yet.</p>';return;}box.innerHTML=rows.slice(0,6).map(r=>`<div class="rounded-xl border border-white/10 bg-black/20 px-3 py-2"><div class="flex justify-between gap-2"><span class="font-semibold text-slate-200 truncate">${esc(r.action)}</span><span class="text-[10px] text-slate-500">${esc(r.time||'')}</span></div><p class="text-[11px] text-slate-500 truncate">${esc(r.details||'')}</p></div>`).join('');}
        function renderIncidents(rows){const box=document.getElementById('guardian-incidents');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No incident clusters yet.</p>';return;}box.innerHTML=rows.slice(0,10).map(r=>`<button type="button" class="block w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-left" data-incident-player="${esc(r.player)}" data-incident-world="${esc(r.world)}"><div class="flex justify-between gap-2"><span class="font-semibold text-slate-100 truncate">${esc(r.player)}</span><span class="text-[11px] text-amber-300">${fmt(r.score)}</span></div><p class="text-[11px] text-slate-500 truncate">${esc(r.world)} chunk ${r.chunkX}, ${r.chunkZ} / ${fmt(r.totalActions)} actions</p></button>`).join('');}
        function renderScores(rows){const box=document.getElementById('guardian-scores');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No scores yet.</p>';return;}box.innerHTML=rows.slice(0,10).map(r=>`<button type="button" data-score-player="${esc(r.player)}" class="block w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-left"><div class="flex justify-between gap-2"><span class="font-semibold text-slate-100 truncate">${esc(r.player)}</span><span class="rounded-lg border px-2 py-0.5 text-[10px] font-bold ${r.severity==='CRITICAL'?'text-rose-300 border-rose-500/20 bg-rose-500/10':r.severity==='HIGH'?'text-amber-300 border-amber-500/20 bg-amber-500/10':'text-sky-300 border-sky-500/20 bg-sky-500/10'}">${esc(r.severity)} ${fmt(r.score)}</span></div><p class="text-[11px] text-slate-500 truncate">${fmt(r.blockBreaks)} breaks / ${fmt(r.containerRemoves)} removals / ${fmt(r.rareHits)} rare</p></button>`).join('');}
        function renderRegions(rows){const box=document.getElementById('guardian-regions');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No protected regions.</p>';return;}box.innerHTML=rows.map(r=>`<div class="flex items-center gap-2 rounded-xl border border-white/10 bg-black/20 px-3 py-2"><button type="button" class="guardian-region flex-1 min-w-0 text-left" data-region='${esc(JSON.stringify(r))}'><span class="block font-semibold text-slate-100 truncate">${esc(r.name)}</span><span class="block text-[11px] text-slate-500 truncate">${esc(r.world)} ${r.minX},${r.minY},${r.minZ} -> ${r.maxX},${r.maxY},${r.maxZ}</span></button><button type="button" data-region-delete="${r.id}" class="text-slate-500 hover:text-rose-300"><span class="material-symbols-outlined text-[16px]">close</span></button></div>`).join('');}
        function renderRegionHits(rows){const box=document.getElementById('guardian-region-hits');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No protected-region hits.</p>';return;}box.innerHTML=rows.slice(0,5).map(r=>`<div class="rounded-xl border border-amber-500/20 bg-amber-500/10 px-3 py-2"><div class="flex justify-between gap-2"><span class="font-semibold text-amber-200 truncate">${esc(r.region)}</span><span class="text-[11px] text-amber-300">${fmt(r.totalActions)}</span></div><p class="text-[11px] text-slate-400 truncate">${esc(r.player)} / ${esc(r.world)}</p></div>`).join('');}
        function renderAlertRules(rows){const box=document.getElementById('guardian-alert-rules');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No alert rules.</p>';return;}box.innerHTML=rows.map(r=>`<div class="flex items-center gap-2 rounded-xl border border-white/10 bg-black/20 px-3 py-2"><button type="button" class="guardian-alert-rule flex-1 min-w-0 text-left" data-rule='${esc(JSON.stringify(r))}'><span class="block font-semibold text-slate-100 truncate">${esc(r.name)}</span><span class="block text-[11px] text-slate-500 truncate">${r.enabled?'Enabled':'Paused'} / ${fmt(r.minActions)} in ${fmt(r.windowSeconds)}s / ${esc(r.action||'any')}</span></button><button type="button" data-rule-delete="${r.id}" class="text-slate-500 hover:text-rose-300"><span class="material-symbols-outlined text-[16px]">close</span></button></div>`).join('');}
        function renderAlertHits(rows){const box=document.getElementById('guardian-alert-hits');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No active rule hits.</p>';return;}box.innerHTML=rows.slice(0,5).map(r=>`<div class="rounded-xl border border-rose-500/20 bg-rose-500/10 px-3 py-2"><div class="flex justify-between gap-2"><span class="font-semibold text-rose-200 truncate">${esc(r.player)}</span><span class="text-[11px] text-rose-300">${fmt(r.count)}</span></div><p class="text-[11px] text-slate-400 truncate">${esc(r.rule)}</p></div>`).join('');}
        function renderRetention(row){const box=document.getElementById('guardian-retention-status');if(!box||!row)return;box.textContent=`Keeping logs for ${fmt(row.logDays)} days. Last update: ${row.updatedTime||'never'}`;const form=document.getElementById('guardian-retention-form');if(form){const days=form.querySelector('[name="logDays"]'),keep=form.querySelector('[name="keepCases"]');if(days)days.value=row.logDays||90;if(keep)keep.checked=row.keepCases!==false;}}
        function renderReplay(rows){const box=document.getElementById('guardian-replay');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No replay events.</p>';return;}box.innerHTML=rows.slice(0,12).map(r=>`<div class="rounded-xl border border-white/10 bg-black/20 px-3 py-2"><div class="flex justify-between gap-2"><span class="font-semibold text-slate-200 truncate">${esc(r.action)} ${esc(r.target)}</span><span class="text-[10px] text-slate-500">${esc(r.time||'')}</span></div><p class="text-[11px] text-slate-500 truncate">${esc(r.player)} / ${esc(r.world)} ${r.x}, ${r.y}, ${r.z}</p></div>`).join('');}
        function renderContainerRestore(rows){const box=document.getElementById('guardian-container-restore');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No removed items matched.</p>';return;}box.innerHTML=rows.slice(0,12).map(r=>`<div class="flex justify-between gap-3 rounded-xl border border-white/10 bg-black/20 px-3 py-2"><span class="truncate text-slate-200">${esc(r.item)}</span><span class="font-mono text-slate-500">${fmt(r.amount)}</span></div>`).join('');}
        async function loadSelectionTools(){if(!selectedEvent)return;const q=new URLSearchParams();q.set('hours','24');q.set('limit','24');q.set('player',selectedEvent.player||'');q.set('world',selectedEvent.world||'');const replay=await apiJson('/api/guardian/replay',q);renderReplay(replay);const c=new URLSearchParams(q);c.set('x',selectedEvent.x||'');c.set('y',selectedEvent.y||'');c.set('z',selectedEvent.z||'');c.set('radius','0');const plan=await apiJson('/api/guardian/container-restore-plan',c);renderContainerRestore(plan);}
        function setText(id,val){const el=document.getElementById(id);if(el)el.textContent=fmt(val);}
        function rowClickAttrs(type,row){return `data-event-type="${esc(type)}" data-event-id="${row.id}" data-player="${esc(row.player)}" data-world="${esc(row.world)}" data-x="${row.x}" data-y="${row.y}" data-z="${row.z}" data-target="${esc(row.target||row.block||row.item||'')}"`;}
        function renderTimeline(rows){const body=document.getElementById('guardian-timeline-table');body.innerHTML='';if(!rows.length){empty(body,6,'No timeline events found');return;}for(const r of rows){const tone=r.type==='block'?(r.action==='PLACE'?'bg-emerald-500/20 text-emerald-300':'bg-rose-500/20 text-rose-300'):(r.action==='ADD'?'bg-emerald-500/20 text-emerald-300':'bg-amber-500/20 text-amber-300');body.insertAdjacentHTML('beforeend',`<tr ${rowClickAttrs(r.type,r)} class="guardian-row border-b border-white/5 hover:bg-white/5 cursor-pointer transition-colors"><td class="px-4 py-3 text-xs text-slate-400 font-mono whitespace-nowrap">${esc(r.time)}</td><td class="px-4 py-3 text-sm text-white font-medium whitespace-nowrap">${esc(r.player)}</td><td class="px-4 py-3 whitespace-nowrap">${badge(r.type+' / '+r.action,tone)}</td><td class="px-4 py-3 text-sm text-slate-300 font-mono whitespace-nowrap">${esc(r.target)}${r.amount?` <span class="text-slate-500">x${fmt(r.amount)}</span>`:''}</td><td class="px-4 py-3 text-sm text-slate-300 font-mono whitespace-nowrap">${loc(r)}</td><td class="px-4 py-3 text-xs text-slate-500 uppercase whitespace-nowrap">${esc(r.source)}</td></tr>`);}}
        function renderBlocks(rows){const body=document.getElementById('guardian-block-table');body.innerHTML='';if(!rows.length){empty(body,6,'No block logs found');return;}for(const r of rows){r.target=r.block;const tone=r.action==='PLACE'?'bg-emerald-500/20 text-emerald-300':'bg-rose-500/20 text-rose-300';body.insertAdjacentHTML('beforeend',`<tr ${rowClickAttrs('block',r)} class="guardian-row border-b border-white/5 hover:bg-white/5 cursor-pointer transition-colors"><td class="px-4 py-3 text-xs text-slate-400 font-mono whitespace-nowrap">${esc(r.time)}</td><td class="px-4 py-3 text-sm text-white font-medium whitespace-nowrap">${esc(r.player)}</td><td class="px-4 py-3 whitespace-nowrap">${badge(r.action,tone)}</td><td class="px-4 py-3 text-sm text-slate-300 font-mono whitespace-nowrap">${esc(r.block)}</td><td class="px-4 py-3 text-sm text-slate-300 font-mono whitespace-nowrap">${loc(r)}</td><td class="px-4 py-3 text-xs text-slate-500 uppercase whitespace-nowrap">${esc(r.source)}</td></tr>`);}}
        function renderContainers(rows){const body=document.getElementById('guardian-container-table');body.innerHTML='';if(!rows.length){empty(body,6,'No container logs found');return;}for(const r of rows){r.target=r.item;const tone=r.action==='ADD'?'bg-emerald-500/20 text-emerald-300':'bg-amber-500/20 text-amber-300';body.insertAdjacentHTML('beforeend',`<tr ${rowClickAttrs('container',r)} class="guardian-row border-b border-white/5 hover:bg-white/5 cursor-pointer transition-colors"><td class="px-4 py-3 text-xs text-slate-400 font-mono whitespace-nowrap">${esc(r.time)}</td><td class="px-4 py-3 text-sm text-white font-medium whitespace-nowrap">${esc(r.player)}</td><td class="px-4 py-3 whitespace-nowrap">${badge(r.action,tone)}</td><td class="px-4 py-3 text-sm text-slate-300 whitespace-nowrap"><span class="font-mono">${fmt(r.amount)}</span> ${esc(r.item)}</td><td class="px-4 py-3 text-sm text-slate-300 font-mono whitespace-nowrap">${loc(r)}</td><td class="px-4 py-3 text-xs text-slate-500 uppercase whitespace-nowrap">${esc(r.source)}</td></tr>`);}}
        function empty(body,cols,text){body.innerHTML=`<tr><td colspan="${cols}" class="px-4 py-8 text-center text-slate-500 whitespace-nowrap">${esc(text)}</td></tr>`;}
        function renderStatus(data){const box=document.getElementById('guardian-status');if(!box)return;if(!data){box.innerHTML='<p>Guardian status unavailable.</p>';return;}box.innerHTML=`<div class="flex items-center justify-between"><span>Database</span><span class="${data.available?'text-emerald-400':'text-rose-300'}">${data.available?'Online':'Offline'}</span></div><div class="flex items-center justify-between"><span>Block rows</span><span class="font-mono text-slate-200">${fmt(data.blockRows)}</span></div><div class="flex items-center justify-between"><span>Container rows</span><span class="font-mono text-slate-200">${fmt(data.containerRows)}</span></div><div class="text-[11px] text-slate-500">CoreProtect required: no</div>`;}
        function renderCases(rows){const box=document.getElementById('guardian-cases');const actions=document.getElementById('guardian-case-actions');const bundle=document.getElementById('guardian-case-bundle');if(actions)actions.classList.toggle('hidden',!selectedCaseId);if(bundle){bundle.href=selectedCaseId?'/api/guardian/cases/bundle?caseId='+encodeURIComponent(selectedCaseId):'#';bundle.classList.toggle('opacity-50',!selectedCaseId);}if(!box)return;if(!rows.length){box.innerHTML='<p class="text-slate-500">No cases yet.</p>';return;}box.innerHTML='';rows.forEach(c=>box.insertAdjacentHTML('beforeend',`<button type="button" data-case-id="${c.id}" class="guardian-case block w-full text-left rounded-xl border ${selectedCaseId==c.id?'border-primary/50 bg-primary/10':'border-white/10 bg-black/20 hover:border-white/20'} px-3 py-2"><div class="flex items-center justify-between gap-2"><span class="font-semibold text-slate-100 truncate">${esc(c.title)}</span><span class="text-[10px] text-slate-500">${esc(c.status)}</span></div><p class="text-[11px] text-slate-500 truncate">${esc(c.player||'No player')} ${esc(c.world||'')}</p></button>`));}
        function renderFilters(rows){const box=document.getElementById('guardian-saved-filters');if(!box)return;if(!rows.length){box.innerHTML='<p class="text-slate-500">No saved filters.</p>';return;}box.innerHTML='';rows.forEach(f=>box.insertAdjacentHTML('beforeend',`<div class="flex items-center gap-2 rounded-xl border border-white/10 bg-black/20 px-2 py-2"><button type="button" data-filter-query="${esc(f.query)}" class="guardian-filter flex-1 min-w-0 text-left text-xs text-slate-300 truncate">${esc(f.name)}</button><button type="button" data-filter-id="${f.id}" class="guardian-filter-delete text-slate-500 hover:text-rose-300"><span class="material-symbols-outlined text-[16px]">close</span></button></div>`));}
        function noteTone(severity){return severity==='ALERT'?'text-rose-300 bg-rose-500/10 border-rose-500/20':severity==='TRUSTED'?'text-emerald-300 bg-emerald-500/10 border-emerald-500/20':severity==='INFO'?'text-sky-300 bg-sky-500/10 border-sky-500/20':'text-amber-300 bg-amber-500/10 border-amber-500/20';}
        function renderPlayerNotes(rows){const box=document.getElementById('guardian-player-notes');if(!box)return;if(!rows||!rows.length){box.innerHTML='<p class="text-slate-500">No watched players yet.</p>';return;}box.innerHTML='';rows.forEach(n=>box.insertAdjacentHTML('beforeend',`<button type="button" data-note-player="${esc(n.player)}" data-note-severity="${esc(n.severity)}" data-note-notes="${esc(n.notes||'')}" class="guardian-note block w-full rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-left hover:border-white/20"><div class="flex items-center justify-between gap-2"><span class="font-semibold text-slate-100 truncate">${esc(n.player)}</span><span class="rounded-lg border px-2 py-0.5 text-[10px] font-bold ${noteTone(n.severity)}">${esc(n.severity)}</span></div><p class="mt-1 truncate text-[11px] text-slate-500">${esc(n.notes||'No notes')}</p></button>`));}
        function selectEvent(el){selectedEvent={type:el.dataset.eventType,id:el.dataset.eventId,player:el.dataset.player,world:el.dataset.world,x:el.dataset.x,y:el.dataset.y,z:el.dataset.z,target:el.dataset.target};document.querySelectorAll('.guardian-row').forEach(r=>r.classList.remove('bg-primary/10'));el.classList.add('bg-primary/10');renderSelection();}
        function renderSelection(){const box=document.getElementById('guardian-selection');const copy=document.getElementById('guardian-copy-tp');const fill=document.getElementById('guardian-fill-action');const attach=document.getElementById('guardian-attach-evidence');const note=document.getElementById('guardian-flag-player');if(!selectedEvent){box.innerHTML='Select an event to inspect it.';copy.disabled=true;fill.disabled=true;if(attach)attach.disabled=true;if(note)note.disabled=true;return;}box.innerHTML=`<div class="space-y-2"><div class="flex items-center justify-between"><span class="text-slate-500">Player</span><span class="font-semibold text-white">${esc(selectedEvent.player)}</span></div><div class="flex items-center justify-between"><span class="text-slate-500">Target</span><span class="font-mono text-slate-200">${esc(selectedEvent.target)}</span></div><div class="flex items-center justify-between"><span class="text-slate-500">Location</span><span class="font-mono text-slate-200">${esc(selectedEvent.world)} ${selectedEvent.x}, ${selectedEvent.y}, ${selectedEvent.z}</span></div><div class="text-[11px] text-slate-500">Evidence id: ${esc(selectedEvent.type)} #${esc(selectedEvent.id)}</div></div>`;copy.disabled=false;fill.disabled=false;if(attach)attach.disabled=false;if(note)note.disabled=false;fillCaseFromSelection();loadSelectionTools();}
        function fillCaseFromSelection(){if(!selectedEvent)return;for(const [id,val] of [['case-player',selectedEvent.player],['case-world',selectedEvent.world],['case-x',selectedEvent.x],['case-y',selectedEvent.y],['case-z',selectedEvent.z],['action-player',selectedEvent.player],['action-world',selectedEvent.world],['action-x',selectedEvent.x],['action-y',selectedEvent.y],['action-z',selectedEvent.z],['note-player',selectedEvent.player]]){const el=document.getElementById(id);if(el)el.value=val||'';}}
        function setTab(tab){activeGuardianTab=tab;document.getElementById('guardian-timeline-view').style.display=tab==='timeline'?'':'none';document.getElementById('guardian-block-view').style.display=tab==='blocks'?'':'none';document.getElementById('guardian-container-view').style.display=tab==='containers'?'':'none';document.querySelectorAll('.guardian-tab').forEach(btn=>{const on=btn.dataset.guardianTab===tab;btn.className='guardian-tab w-full justify-center px-4 py-2 rounded-xl text-xs font-bold transition-all '+(on?'bg-primary text-black shadow-lg':'text-slate-400 hover:text-white hover:bg-white/5');});updatePager();}
        function setWorkspace(tab){document.querySelectorAll('[data-guardian-workspace]').forEach(panel=>panel.classList.toggle('hidden',panel.dataset.guardianWorkspace!==tab));document.querySelectorAll('[data-guardian-workspace-tab]').forEach(btn=>{const on=btn.dataset.guardianWorkspaceTab===tab;btn.className='guardian-workspace-tab w-full rounded-md px-3 py-2.5 text-xs font-bold transition-all '+(on?'bg-primary text-black shadow-lg':'text-slate-400 hover:bg-white/5 hover:text-white');});}
        function updateExports(p){const b=document.getElementById('guardian-export-blocks');const c=document.getElementById('guardian-export-containers');if(b)b.href='/api/guardian/export/blocks?'+p;if(c)c.href='/api/guardian/export/containers?'+p;}
        async function loadWorlds(){const select=document.getElementById('guardian-world');if(!select)return;select.innerHTML='<option value="">All worlds</option>';const worlds=asArray(await apiJson('/api/guardian/worlds'));worlds.forEach(w=>select.insertAdjacentHTML('beforeend',`<option value="${esc(w)}">${esc(w)}</option>`));}
        let coreProtectLoadSeq=0;
        async function loadCoreProtect(){const el=document.getElementById('coreprotect-status');if(!el)return;const importForm=document.getElementById('coreprotect-import');const setDisabled=disabled=>{if(!importForm)return;importForm.dataset.available=disabled?'false':'true';importForm.setAttribute('aria-disabled',String(disabled));importForm.querySelectorAll('input,select,button,textarea').forEach(control=>{control.disabled=disabled;});};const seq=++coreProtectLoadSeq;el.textContent='Checking';el.className='text-slate-500';setDisabled(true);const data=await apiJson('/api/guardian/coreprotect/status');if(seq!==coreProtectLoadSeq)return;const available=Boolean(data&&data.available);el.textContent=available?'Ready':'Unavailable';el.className=available?'text-emerald-400':'text-slate-500';if(importForm)importForm.title=available?'':(data&&data.message||'CoreProtect is unavailable on this server.');setDisabled(!available);}
        form.addEventListener('submit',e=>{e.preventDefault();loadGuardian();},guardianEventOptions);
        document.addEventListener('click',e=>{const workspace=e.target.closest('[data-guardian-workspace-tab]');if(workspace){setWorkspace(workspace.dataset.guardianWorkspaceTab);return;}const pager=e.target.closest('[data-guardian-page]');if(!pager)return;const total=activeTotalPages();guardianPage[activeGuardianTab]=Math.min(Math.max((guardianPage[activeGuardianTab]||1)+Number(pager.dataset.guardianPage||0),1),total);renderPagedGuardian();},guardianEventOptions);
        document.addEventListener('click',async e=>{const row=e.target.closest('.guardian-row');if(row)selectEvent(row);const tab=e.target.closest('.guardian-tab');if(tab)setTab(tab.dataset.guardianTab);const f=e.target.closest('.guardian-filter');if(f){const q=new URLSearchParams(f.dataset.filterQuery||'');for(const el of form.elements){if(el.name)el.value=q.get(el.name)||'';}loadGuardian();}const noteRow=e.target.closest('.guardian-note');if(noteRow){const p=document.getElementById('note-player'),s=document.getElementById('note-severity'),n=document.getElementById('note-notes');if(p)p.value=noteRow.dataset.notePlayer||'';if(s)s.value=noteRow.dataset.noteSeverity||'WATCH';if(n)n.value=noteRow.dataset.noteNotes||'';}const del=e.target.closest('.guardian-filter-delete');if(del){const body=new URLSearchParams();body.set('id',del.dataset.filterId);await fetch('/api/guardian/saved-filters/delete',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});loadGuardian();}const c=e.target.closest('.guardian-case');if(c){selectedCaseId=c.dataset.caseId;loadGuardian();}const status=e.target.closest('[data-case-status]');if(status){if(!selectedCaseId){showToast('Select a case first','error');return;}const body=new URLSearchParams();body.set('caseId',selectedCaseId);body.set('status',status.dataset.caseStatus);const res=await fetch('/api/guardian/cases/update',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});const out=await res.json().catch(()=>({success:false,error:'Update failed'}));showToast(out.success?'Case updated':(out.error||'Update failed'),out.success?'success':'error');loadGuardian();} },guardianEventOptions);
        document.addEventListener('click',async e=>{const tool=e.target.closest('[data-guardian-tool]');if(!tool)return;const box=document.getElementById('guardian-tool-result');if(!selectedEvent){showToast('Select an event first','error');return;}const q=params();q.set('player',selectedEvent.player||'');q.set('world',selectedEvent.world||'');q.set('x',selectedEvent.x||'');q.set('y',selectedEvent.y||'');q.set('z',selectedEvent.z||'');q.set('radius',q.get('radius')||'5');q.set('limit','12');const name=tool.dataset.guardianTool;const path=name==='near'?'/api/guardian/near':name==='lookup'?'/api/guardian/lookup':'/api/guardian/'+name;const out=await apiJson(path,q);if(!box)return;if(name.startsWith('has-')){box.innerHTML=`<span class="${out&&out.matches?'text-emerald-300':'text-slate-500'}">${esc(name)}: ${out&&out.matches?'yes':'no'}</span>`;return;}const count=out&&out.count?out.count:{blocks:(out&&out.blocks||[]).length,containers:(out&&out.containers||[]).length};box.innerHTML=`<div class="space-y-1"><div class="flex justify-between"><span>Blocks</span><span class="font-mono">${fmt(count.blocks)}</span></div><div class="flex justify-between"><span>Containers</span><span class="font-mono">${fmt(count.containers)}</span></div></div>`;},guardianEventOptions);
        document.addEventListener('click',async e=>{const score=e.target.closest('[data-score-player]');if(score){form.elements.player.value=score.dataset.scorePlayer||'';loadGuardian();}const incident=e.target.closest('[data-incident-player]');if(incident){form.elements.player.value=incident.dataset.incidentPlayer||'';form.elements.world.value=incident.dataset.incidentWorld||'';loadGuardian();}const regionDel=e.target.closest('[data-region-delete]');if(regionDel){const out=await postForm('/api/guardian/protected-regions/delete',{id:regionDel.dataset.regionDelete});showToast(out.success?'Region deleted':(out.error||'Delete failed'),out.success?'success':'error');loadGuardian();}const ruleDel=e.target.closest('[data-rule-delete]');if(ruleDel){const out=await postForm('/api/guardian/alert-rules/delete',{id:ruleDel.dataset.ruleDelete});showToast(out.success?'Rule deleted':(out.error||'Delete failed'),out.success?'success':'error');loadGuardian();}},guardianEventOptions);
        const filterForm=document.getElementById('guardian-filter-save');
        if(filterForm){filterForm.addEventListener('submit',async e=>{e.preventDefault();const data=new FormData(filterForm);data.set('query',params().toString());const res=await fetch('/api/guardian/saved-filters',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});const out=await res.json().catch(()=>({success:false,error:'Save failed'}));showToast(out.success?'Filter saved':(out.error||'Save failed'),out.success?'success':'error');loadGuardian();});}
        const caseForm=document.getElementById('guardian-case-form');
        if(caseForm){caseForm.addEventListener('submit',async e=>{e.preventDefault();const res=await fetch('/api/guardian/cases',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(new FormData(caseForm))});const out=await res.json().catch(()=>({success:false,error:'Case failed'}));if(out.case)selectedCaseId=out.case.id;showToast(out.success?'Case created':(out.error||'Case failed'),out.success?'success':'error');loadGuardian();});}
        const noteForm=document.getElementById('guardian-note-form');
        if(noteForm){noteForm.addEventListener('submit',async e=>{e.preventDefault();const res=await fetch('/api/guardian/player-notes',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(new FormData(noteForm))});const out=await res.json().catch(()=>({success:false,error:'Note failed'}));showToast(out.success?'Player note saved':(out.error||'Note failed'),out.success?'success':'error');if(out.notes)renderPlayerNotes(out.notes);});}
        const noteDelete=document.getElementById('guardian-note-delete');
        if(noteDelete){noteDelete.addEventListener('click',async()=>{const player=document.getElementById('note-player')?.value||'';if(!player){showToast('Choose a player first','error');return;}const body=new URLSearchParams();body.set('player',player);const res=await fetch('/api/guardian/player-notes/delete',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});const out=await res.json().catch(()=>({success:false,error:'Delete failed'}));showToast(out.success?'Player note deleted':(out.error||'Delete failed'),out.success?'success':'error');if(out.notes)renderPlayerNotes(out.notes);});}
        const regionForm=document.getElementById('guardian-region-form');
        if(regionForm){regionForm.addEventListener('submit',async e=>{e.preventDefault();const out=await postForm('/api/guardian/protected-regions',new FormData(regionForm));showToast(out.success?'Region saved':(out.error||'Region failed'),out.success?'success':'error');if(out.regions)renderRegions(out.regions);loadGuardian();});}
        const alertRuleForm=document.getElementById('guardian-alert-rule-form');
        if(alertRuleForm){alertRuleForm.addEventListener('submit',async e=>{e.preventDefault();const out=await postForm('/api/guardian/alert-rules',new FormData(alertRuleForm));showToast(out.success?'Rule saved':(out.error||'Rule failed'),out.success?'success':'error');if(out.rules)renderAlertRules(out.rules);loadGuardian();});}
        const alertEvaluate=document.getElementById('guardian-alert-evaluate');
        if(alertEvaluate){alertEvaluate.addEventListener('click',async()=>{const out=await postForm('/api/guardian/alert-rules/evaluate',{autoCase:'on'});showToast(out.success?`Evaluated ${fmt((out.hits||[]).length)} hits`:(out.error||'Evaluate failed'),out.success?'success':'error');if(out.hits)renderAlertHits(out.hits);loadGuardian();});}
        const retentionForm=document.getElementById('guardian-retention-form');
        if(retentionForm){retentionForm.addEventListener('submit',async e=>{e.preventDefault();const out=await postForm('/api/guardian/retention',new FormData(retentionForm));showToast(out.success?'Retention saved':(out.error||'Retention failed'),out.success?'success':'error');if(out.policy)renderRetention(out.policy);});}
        const retentionApply=document.getElementById('guardian-retention-apply');
        if(retentionApply){retentionApply.addEventListener('click',async()=>{if(!confirm('Apply Guardian retention policy now? This purges old logs.'))return;const out=await postForm('/api/guardian/retention/apply',{});showToast(out.message||out.error||'Retention finished',out.success?'success':'error');if(out.success)loadGuardian();});}
        const actionForm=document.getElementById('guardian-action-form');
        function renderActionDiff(box,diff){if(!box||!diff)return;box.classList.remove('hidden');box.innerHTML=`<div class="space-y-1"><div class="flex justify-between"><span>Matched rows</span><span class="font-mono">${fmt(diff.blockRows)} blocks / ${fmt(diff.containerRows)} containers</span></div><div class="flex justify-between"><span>Block diff</span><span class="font-mono">${fmt(diff.blockBreaks)} breaks / ${fmt(diff.blockPlaces)} places</span></div><div class="flex justify-between"><span>Container diff</span><span class="font-mono">${fmt(diff.containerRemovedItems)} removed / ${fmt(diff.containerAddedItems)} added</span></div>${(diff.topTargets||[]).length?`<div class="pt-1 text-slate-500">Top: ${(diff.topTargets||[]).slice(0,4).map(t=>esc(t.item)+' x'+fmt(t.amount)).join(', ')}</div>`:''}</div>`;}
        if(actionForm){actionForm.addEventListener('submit',async e=>{e.preventDefault();const data=new FormData(actionForm);const mode=data.get('mode')||'rollback';const box=document.getElementById('guardian-action-result');const diff=await apiJson('/api/guardian/preview-diff',new URLSearchParams(data));renderActionDiff(box,diff);const preview=data.get('preview')==='on'||data.get('preview')==='true';if(!preview){const total=(diff?diff.blockRows+diff.containerRows:0);if(!confirm(`${mode.toUpperCase()} will touch ${fmt(total)} matched Guardian rows. Continue?`))return;}const res=await fetch('/api/guardian/'+mode,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});const out=await res.json().catch(()=>({success:false,error:'Action failed'}));if(box){box.classList.remove('hidden');box.insertAdjacentHTML('beforeend',`<div class="mt-2 border-t border-white/10 pt-2">${esc(out.message||out.error||'Action finished')}</div>`);}showToast(out.message||out.error||'Guardian action finished',out.success?'success':'error');if(out.success&&!out.preview)loadGuardian();});}
        const purgeForm=document.getElementById('guardian-purge-form');
        if(purgeForm){purgeForm.addEventListener('submit',async e=>{e.preventDefault();if(!confirm('Purge matching Guardian logs? This cannot be undone.'))return;const res=await fetch('/api/guardian/purge',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(new FormData(purgeForm))});const out=await res.json().catch(()=>({success:false,error:'Purge failed'}));showToast(out.message||out.error||'Purge finished',out.success?'success':'error');if(out.success)loadGuardian();});}
        const cpForm=document.getElementById('coreprotect-import');
        if(cpForm){cpForm.addEventListener('submit',async e=>{e.preventDefault();if(cpForm.dataset.available!=='true'){showToast(cpForm.title||'CoreProtect is unavailable on this server.','error');return;}const res=await fetch('/api/guardian/coreprotect/import',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(new FormData(cpForm))});const out=await res.json().catch(()=>({success:false,error:'Import failed'}));showToast(out.message||out.error||'Import finished',out.success?'success':'error');loadGuardian();});}
        const copyTp=document.getElementById('guardian-copy-tp');
        if(copyTp){copyTp.addEventListener('click',()=>{if(!selectedEvent)return;const cmd=`/tp ${selectedEvent.x} ${selectedEvent.y} ${selectedEvent.z}`;navigator.clipboard?.writeText(cmd);showToast('Teleport command copied','success');});}
        const fillAction=document.getElementById('guardian-fill-action');
        if(fillAction){fillAction.addEventListener('click',()=>fillCaseFromSelection());}
        const flagPlayer=document.getElementById('guardian-flag-player');
        if(flagPlayer){flagPlayer.addEventListener('click',()=>{fillCaseFromSelection();const notes=document.getElementById('note-notes');if(notes&&!notes.value&&selectedEvent){notes.value=`${selectedEvent.target} at ${selectedEvent.world} ${selectedEvent.x}, ${selectedEvent.y}, ${selectedEvent.z}`;}notes?.focus();});}
        const attachEvidence=document.getElementById('guardian-attach-evidence');
        if(attachEvidence){attachEvidence.addEventListener('click',async()=>{if(!selectedEvent){showToast('Select an event first','error');return;}if(!selectedCaseId){showToast('Select or create a case first','error');return;}const body=new URLSearchParams();body.set('caseId',selectedCaseId);body.set('eventType',selectedEvent.type);body.set('eventId',selectedEvent.id);body.set('label',`${selectedEvent.player} ${selectedEvent.target} at ${selectedEvent.world} ${selectedEvent.x},${selectedEvent.y},${selectedEvent.z}`);const res=await fetch('/api/guardian/cases/evidence',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});const out=await res.json().catch(()=>({success:false,error:'Evidence failed'}));showToast(out.success?'Evidence attached':(out.error||'Evidence failed'),out.success?'success':'error');});}
        setWorkspace('overview');loadWorlds().then(loadGuardian);loadCoreProtect();
        })();
        </script>
        """;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
