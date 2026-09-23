package dash.web;

import dash.Dash;
import dash.security.FilePermissions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class StaffPage {

    private StaffPage() {
    }

    public static String render(String message) {
        return render(message, "");
    }

    public static String render(String message, String rawQuery) {
        Path dataDir = Dash.getInstance().getDataFolder().toPath();
        List<Item> items = load(dataDir);
        boolean canModerate = HtmlTemplate.can("dash.web.players.moderate");
        if (!canModerate) {
            String viewer = HtmlTemplate.currentUiUser();
            items = items.stream()
                    .filter(item -> viewer != null && !viewer.isBlank() && viewer.equalsIgnoreCase(item.author()))
                    .toList();
        }
        String viewer = HtmlTemplate.currentUiUser();
        String view = query(rawQuery, "view");
        if (view.isBlank()) view = canModerate ? "inbox" : "mine";
        if (!List.of("inbox", "mine", "new", "notes", "links").contains(view)
                || (!canModerate && ("inbox".equals(view) || "notes".equals(view) || "links".equals(view)))) view = "mine";
        final String selectedView = view;
        String statusFilter = query(rawQuery, "status");
        String search = query(rawQuery, "q").toLowerCase();
        List<Item> visible = items.stream()
                .filter(item -> switch (selectedView) {
                    case "mine" -> viewer != null && viewer.equalsIgnoreCase(item.author()) && "ticket".equals(item.type());
                    case "notes" -> canModerate && "note".equals(item.type());
                    default -> "ticket".equals(item.type());
                })
                .filter(item -> statusFilter.isBlank() || statusFilter.equalsIgnoreCase(item.status()))
                .filter(item -> search.isBlank() || (item.title() + " " + item.target() + " " + item.author()).toLowerCase().contains(search))
                .toList();
        int page = Math.max(1, integer(query(rawQuery, "page"), 1));
        int pageSize = 15;
        int pages = Math.max(1, (visible.size() + pageSize - 1) / pageSize);
        page = Math.min(page, pages);
        List<Item> pageItems = visible.subList(Math.min(visible.size(), (page - 1) * pageSize), Math.min(visible.size(), page * pageSize));
        long open = items.stream().filter(item -> "ticket".equals(item.type()) && "open".equals(item.status())).count();
        long reviewing = items.stream().filter(item -> "ticket".equals(item.type()) && "reviewing".equals(item.status())).count();
        String banner = message == null || message.isBlank() ? ""
                : "<div class='rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-3 text-sm text-cyan-100'>" + escape(message) + "</div>";

        String content = HtmlTemplate.statsHeader() + styles()
                + "<main class='ticket-main flex-1 min-w-0 p-4 sm:p-6'><div class='max-w-7xl mx-auto space-y-4'>"
                + "<header class='ticket-header'><div><span class='ticket-kicker'><span class='material-symbols-outlined'>support_agent</span>Reports</span><h1>Tickets</h1>"
                + "<p>Player reports, personal submissions and private staff context.</p></div><a class='ticket-btn primary' href='/staff?view=new'><span class='material-symbols-outlined'>add</span>New report</a></header>"
                + banner
                + "<section class='ticket-metrics'>" + metricStrip("Open", String.valueOf(open)) + metricStrip("Reviewing", String.valueOf(reviewing))
                + metricStrip("Total", String.valueOf(items.stream().filter(item -> "ticket".equals(item.type())).count()))
                + metricStrip("Page", page + " / " + pages) + "</section>"
                + "<nav class='ticket-tabs'>" + viewTab("inbox", "Inbox", view, canModerate) + viewTab("mine", "My Tickets", view, true)
                + viewTab("new", "New Report", view, true) + viewTab("notes", "Staff Notes", view, canModerate)
                + viewTab("links", "Report Links", view, canModerate) + "</nav>"
                + ("new".equals(view) ? "<section class='ticket-form-grid'>" + formCard("Submit Report", "staff_ticket_create",
                        input("title", "Short Title", "What happened?")
                                + input("target_player", "Player Involved", "Optional")
                                + select("category", "Category", new String[]{"player", "griefing", "technical", "appeal", "bug", "other"})
                                + select("priority", "Urgency", new String[]{"normal", "high", "urgent", "low"})
                                + textarea("body", "Report Details", "Add names, time, location and what staff should check."))
                + (canModerate ? formCard("Private Staff Note", "staff_note_create",
                        input("target_player", "Player", "Optional")
                                + input("title", "Note Title", "Context")
                                + textarea("body", "Note", "What should staff remember?")) : "")
                + "</section>" : "links".equals(view) ? reportLinksCard(rawQuery)
                : "<section class='ticket-list-head'><form method='get' action='/staff'><input type='hidden' name='view' value='" + escape(view) + "'><input name='q' value='" + escape(query(rawQuery, "q")) + "' placeholder='Search title, player or author'><select name='status'><option value=''>All statuses</option>" + filterOption("open", statusFilter) + filterOption("reviewing", statusFilter) + filterOption("waiting_player", statusFilter) + filterOption("resolved", statusFilter) + filterOption("dismissed", statusFilter) + "</select><button class='ticket-btn'>Filter</button></form><span>" + visible.size() + " results</span></section><section class='ticket-queue'>" + queue(pageItems, canModerate) + "</section>" + pager(view, page, pages, query(rawQuery, "q"), statusFilter))
                + "</div></main>" + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Staff", "/staff", content);
    }

    private static String viewTab(String id, String label, String active, boolean visible) { return visible ? "<a href='/staff?view=" + id + "' class='" + (id.equals(active) ? "is-active" : "") + "'>" + label + "</a>" : ""; }
    private static String metricStrip(String label, String value) { return "<article><small>" + label + "</small><b>" + escape(value) + "</b></article>"; }
    private static String filterOption(String value, String selected) { return "<option value='" + value + "'" + (value.equals(selected) ? " selected" : "") + ">" + value.substring(0, 1).toUpperCase() + value.substring(1) + "</option>"; }
    private static String pager(String view, int page, int pages, String search, String status) { if (pages <= 1) return ""; String base = "/staff?view=" + view + "&q=" + url(search) + "&status=" + url(status) + "&page="; return "<nav class='ticket-pager'><a " + (page > 1 ? "href='" + base + (page - 1) + "'" : "aria-disabled='true'") + "><span class='material-symbols-outlined'>chevron_left</span>Previous</a><span>Page " + page + " of " + pages + "</span><a " + (page < pages ? "href='" + base + (page + 1) + "'" : "aria-disabled='true'") + ">Next<span class='material-symbols-outlined'>chevron_right</span></a></nav>"; }

    private static String styles() { return "<style>.ticket-main{color:#cbd5e1}.ticket-main input:not([type=hidden]),.ticket-main select,.ticket-main textarea{width:100%;min-width:0;border:1px solid rgba(100,116,139,.38)!important;border-radius:6px!important;background:#0a1020!important;color:#e2e8f0!important;-webkit-text-fill-color:#e2e8f0;padding:.65rem .75rem;line-height:1.25;box-shadow:inset 0 1px 0 rgba(255,255,255,.025);outline:none}.ticket-main input::placeholder,.ticket-main textarea::placeholder{color:#64748b!important;-webkit-text-fill-color:#64748b}.ticket-main input:not([type=hidden]):focus,.ticket-main select:focus,.ticket-main textarea:focus{border-color:rgba(34,211,238,.7)!important;box-shadow:0 0 0 3px rgba(34,211,238,.1)!important}.ticket-main input:-webkit-autofill,.ticket-main input:-webkit-autofill:focus{-webkit-box-shadow:0 0 0 1000px #0a1020 inset!important;-webkit-text-fill-color:#e2e8f0!important}.ticket-main select{color-scheme:dark}.ticket-main textarea{min-height:120px;resize:vertical}.ticket-header{display:flex;align-items:flex-end;justify-content:space-between;gap:1rem;border-bottom:1px solid rgba(100,116,139,.24);padding:.8rem 0 1rem}.ticket-kicker{display:flex;align-items:center;gap:.35rem;font-size:.64rem;font-weight:800;text-transform:uppercase;color:#67e8f9}.ticket-kicker span{font-size:17px}.ticket-header h1{margin:.3rem 0 0;font-size:1.5rem;color:#f8fafc}.ticket-header p{margin:.2rem 0 0;font-size:.72rem;color:#64748b}.ticket-btn{display:inline-flex;align-items:center;justify-content:center;gap:.3rem;min-height:34px;border:1px solid rgba(100,116,139,.3);border-radius:6px;padding:0 .65rem;background:rgba(2,6,23,.35);font-size:.62rem;font-weight:800;color:#cbd5e1}.ticket-btn span{font-size:16px}.ticket-btn.primary{border-color:rgba(34,211,238,.34);background:rgba(8,145,178,.12);color:#67e8f9}.ticket-metrics{display:grid;grid-template-columns:repeat(4,1fr);overflow:hidden;border:1px solid rgba(100,116,139,.22);border-radius:7px}.ticket-metrics article{padding:.7rem;border-right:1px solid rgba(100,116,139,.18);background:rgba(2,6,23,.25)}.ticket-metrics small,.ticket-metrics b{display:block}.ticket-metrics small{font-size:.55rem;text-transform:uppercase;color:#64748b}.ticket-metrics b{margin-top:.15rem;font-size:.85rem;color:#e2e8f0}.ticket-tabs{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));overflow:hidden;border:1px solid rgba(100,116,139,.22);border-radius:7px}.ticket-tabs a{border-right:1px solid rgba(100,116,139,.18);padding:.65rem;text-align:center;font-size:.65rem;font-weight:800;color:#64748b}.ticket-tabs a.is-active{background:rgba(8,145,178,.13);color:#67e8f9}.ticket-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem}.ticket-form-grid>article,.ticket-queue article{border-radius:7px!important}.ticket-list-head{display:flex;align-items:center;justify-content:space-between;gap:.7rem}.ticket-list-head form{display:grid;grid-template-columns:minmax(180px,1fr) 150px auto;gap:.45rem;flex:1}.ticket-list-head input,.ticket-list-head select{border-radius:6px!important}.ticket-queue form input[name=reply]{flex:1 1 320px}.ticket-queue form select{min-width:160px}.ticket-list-head>span{font-size:.6rem;color:#64748b}.ticket-queue{display:grid;gap:.55rem}.ticket-pager{display:flex;align-items:center;justify-content:center;gap:1rem}.ticket-pager a{display:flex;align-items:center;gap:.2rem;font-size:.62rem;font-weight:800;color:#67e8f9}.ticket-pager a[aria-disabled=true]{pointer-events:none;color:#475569}.ticket-pager span{font-size:.6rem;color:#64748b}@media(max-width:700px){.ticket-header{align-items:flex-start;flex-direction:column}.ticket-metrics,.ticket-tabs{grid-template-columns:repeat(2,1fr)}.ticket-form-grid{grid-template-columns:1fr}.ticket-list-head{align-items:stretch;flex-direction:column}.ticket-list-head form{grid-template-columns:1fr}}@media(prefers-reduced-motion:reduce){.ticket-main *{animation:none!important;transition:none!important}}</style>"; }

    private static String query(String raw, String key) { if (raw == null) return ""; for (String pair : raw.split("&")) { String[] parts = pair.split("=", 2); if (parts.length > 0 && key.equals(parts[0])) try { return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : ""; } catch (Exception ignored) { return ""; } } return ""; }
    private static int integer(String raw, int fallback) { try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; } }
    private static String url(String value) { return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }

    private static String queue(List<Item> items, boolean canModerate) {
        StringBuilder queue = new StringBuilder();
        for (Item item : items) {
            boolean note = "note".equalsIgnoreCase(item.type());
            if (note && !canModerate) {
                continue;
            }
            queue.append("<article class='rounded-2xl border border-slate-800 bg-slate-950/35 p-4'>")
                    .append("<div class='flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3'><div class='min-w-0'>")
                    .append("<div class='flex flex-wrap items-center gap-2 mb-2'>")
                    .append(pill(note ? "Staff note" : "Report", note ? "slate" : "cyan"))
                    .append(pill(item.status(), statusColor(item.status())))
                    .append(pill(item.priority(), priorityColor(item.priority())))
                    .append("</div><p class='text-sm font-bold text-white break-words'>").append(escape(item.title())).append("</p>")
                    .append(item.target().isBlank() ? "" : "<p class='text-xs text-slate-500 mt-1'>Player: " + escape(item.target()) + "</p>")
                    .append("<p class='mt-1 text-[10px] font-mono text-slate-600'>#").append(escape(item.id().substring(0, Math.min(8, item.id().length()))))
                    .append(" · ").append(escape(Instant.ofEpochMilli(item.createdAt()).toString())).append(" · by ").append(escape(item.author())).append("</p>")
                    .append("</div>");
            if (canModerate) {
                queue.append("<form method='post' action='/action' class='flex flex-col sm:flex-row gap-2 sm:min-w-[240px]'><input type='hidden' name='action' value='staff_ticket_status'>")
                        .append("<input type='hidden' name='ticket_id' value='").append(escape(item.id())).append("'>")
                        .append("<select name='status'>").append(statusOptions(item.status())).append("</select>")
                        .append("<button class='rounded-xl border border-slate-700 px-3 py-2 text-xs font-bold text-slate-200'>Save</button></form>")
                        .append("<form method='post' action='/action' onsubmit=\"return confirm('Delete this ticket permanently?');\"><input type='hidden' name='action' value='staff_ticket_delete'><input type='hidden' name='ticket_id' value='")
                        .append(escape(item.id())).append("'><button class='h-9 w-9 rounded-xl border border-rose-500/25 bg-rose-500/10 text-rose-300' title='Delete ticket' aria-label='Delete ticket'><span class='material-symbols-outlined text-[18px]'>delete</span></button></form>");
            }
            queue.append("</div>")
                    .append("<p class='mt-3 text-sm text-slate-300 whitespace-pre-wrap'>" + escape(item.body()) + "</p>")
                    .append(canModerate && !note ? "<form method='post' action='/action' class='mt-3 flex flex-col sm:flex-row gap-2'><input type='hidden' name='action' value='staff_ticket_reply'><input type='hidden' name='ticket_id' value='" + escape(item.id()) + "'><input name='reply' maxlength='2000' required placeholder='Reply to the reporter or request more information'><button class='ticket-btn primary'>Send reply</button></form>" : "")
                    .append("</article>");
        }
        if (queue.isEmpty()) {
            queue.append("<div class='rounded-2xl border border-slate-800 bg-slate-950/35 p-6 text-center text-sm text-slate-500'>No reports yet.</div>");
        }
        return queue.toString();
    }

    private static String pill(String text, String color) {
        String classes = switch (color) {
            case "rose" -> "bg-rose-500/15 text-rose-300 border-rose-500/30";
            case "amber" -> "bg-amber-500/15 text-amber-300 border-amber-500/30";
            case "emerald" -> "bg-emerald-500/15 text-emerald-300 border-emerald-500/30";
            case "cyan" -> "bg-cyan-500/15 text-cyan-300 border-cyan-500/30";
            default -> "bg-slate-700/40 text-slate-300 border-slate-600/60";
        };
        return "<span class='rounded-full border px-2.5 py-1 text-[11px] font-bold " + classes + "'>" + escape(text) + "</span>";
    }

    private static String statusColor(String status) {
        String value = status == null ? "" : status.toLowerCase();
        if (value.contains("done") || value.contains("resolved")) return "emerald";
        if (value.contains("review")) return "amber";
        return "cyan";
    }

    private static String priorityColor(String priority) {
        String value = priority == null ? "" : priority.toLowerCase();
        if (value.contains("urgent")) return "rose";
        if (value.contains("high")) return "amber";
        return "slate";
    }

    public static synchronized String create(Path dataDir, String type, String title, String body, String priority, String target, String author) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            return "Title and details are required.";
        }
        if (dataDir == null || title.length() > 160 || body.length() > 8000 || (target != null && target.length() > 64)) {
            return "Staff item is too large or the storage path is unavailable.";
        }
        String safeType = "note".equalsIgnoreCase(type) ? "note" : "ticket";
        String safePriority = switch (priority == null ? "" : priority.toLowerCase()) {
            case "low", "high", "urgent" -> priority.toLowerCase();
            default -> "normal";
        };
        Item item = new Item(UUID.randomUUID().toString(), safeType, "open", safePriority,
                author == null || author.isBlank() ? "web" : author.trim(), title.trim(), body.trim(), target == null ? "" : target.trim(), Instant.now().toEpochMilli());
        List<Item> items = load(dataDir);
        items.add(0, item);
        return save(dataDir, items) ? "Staff item created." : "Staff item could not be saved.";
    }

    public static synchronized String createDetailed(Path dataDir, String title, String body, String priority,
            String target, String author, String category) {
        String categorizedBody = "Category: " + safeCategory(category) + "\n\n" + (body == null ? "" : body);
        return create(dataDir, "ticket", title, categorizedBody, priority, target, author);
    }

    static String safeCategory(String category) {
        String value = category == null ? "" : category.trim().toLowerCase();
        return List.of("player", "griefing", "technical", "appeal", "bug", "other").contains(value)
                ? value : "other";
    }

    static String categoryOptions(String selected) {
        StringBuilder out = new StringBuilder();
        for (String category : List.of("player", "griefing", "technical", "appeal", "bug", "other")) {
            out.append("<option value='").append(category).append("'")
                    .append(category.equalsIgnoreCase(selected == null ? "" : selected) ? " selected" : "")
                    .append(">").append(Character.toUpperCase(category.charAt(0))).append(category.substring(1)).append("</option>");
        }
        return out.toString();
    }

    private static String reportLinksCard(String rawQuery) {
        String token = query(rawQuery, "report_token");
        String generated = token.isBlank() ? "" : "<div class='rounded-xl border border-emerald-400/25 bg-emerald-500/10 p-4'><b class='text-sm text-emerald-200'>One-time link created</b><div class='mt-2 flex gap-2'><input id='report-link-output' readonly value='/report?token=" + escape(token) + "'><button type='button' class='ticket-btn' onclick=\"navigator.clipboard.writeText(document.getElementById('report-link-output').value)\">Copy</button></div><script>var o=document.getElementById('report-link-output');o.value=location.origin+(window.dashBasePath||'')+o.value;</script></div>";
        return "<section class='ticket-form-grid'><article class='rounded-3xl border border-glass-border bg-glass-surface p-5'><h2 class='text-lg font-bold text-white'>Create a one-time report page</h2><p class='mt-1 text-xs text-slate-400'>The secret link expires automatically and closes after its first successful submission.</p>" + generated
                + "<form method='post' action='/action' class='mt-4 space-y-3'><input type='hidden' name='action' value='staff_report_link_create'>"
                + input("target_player", "Pre-linked player", "Optional")
                + select("category", "Default category", new String[]{"player", "griefing", "technical", "appeal", "bug", "other"})
                + "<label class='block'><span class='mb-1 block text-[11px] uppercase text-slate-500'>Expires after</span><select name='lifetime_minutes'><option value='30'>30 minutes</option><option value='120'>2 hours</option><option value='1440' selected>24 hours</option><option value='10080'>7 days</option></select></label>"
                + "<button class='ticket-btn primary w-full'>Create private link</button></form></article>"
                + "<article class='rounded-3xl border border-glass-border bg-glass-surface p-5'><h2 class='text-lg font-bold text-white'>Built-in safeguards</h2><ul class='mt-3 space-y-2 text-sm text-slate-400'><li>Single successful submission</li><li>Hashed token storage</li><li>Automatic expiry</li><li>8 KB report limit</li><li>Bot trap and no dashboard access</li></ul></article></section>";
    }

    public static synchronized String updateStatus(Path dataDir, String id, String status) {
        String safeStatus = status == null ? "" : status.toLowerCase();
        if (!List.of("open", "reviewing", "waiting_player", "resolved", "dismissed").contains(safeStatus)) {
            return "Invalid staff status.";
        }
        List<Item> items = load(dataDir);
        boolean changed = false;
        List<Item> updated = new ArrayList<>();
        for (Item item : items) {
            if (item.id().equals(id)) {
                updated.add(new Item(item.id(), item.type(), safeStatus, item.priority(), item.author(), item.title(), item.body(), item.target(), item.createdAt()));
                changed = true;
            } else {
                updated.add(item);
            }
        }
        return !changed ? "Staff item not found."
                : save(dataDir, updated) ? "Staff status updated." : "Staff status could not be saved.";
    }

    public static synchronized String delete(Path dataDir, String id) {
        if (id == null || id.isBlank()) return "Staff item not found.";
        List<Item> items = load(dataDir);
        boolean removed = items.removeIf(item -> item.id().equals(id));
        return !removed ? "Staff item not found."
                : save(dataDir, items) ? "Staff item deleted." : "Staff item could not be deleted.";
    }

    public static synchronized String appendReply(Path dataDir, String id, String actor, String reply) {
        String safeReply = reply == null ? "" : reply.trim();
        if (id == null || id.isBlank() || safeReply.isBlank() || safeReply.length() > 2000) return "A reply is required.";
        List<Item> items = load(dataDir);
        boolean changed = false;
        List<Item> updated = new ArrayList<>(items.size());
        for (Item item : items) {
            if (item.id().equals(id) && "ticket".equals(item.type())) {
                String addition = "\n\n--- Staff reply · " + (actor == null ? "staff" : actor) + " · " + Instant.now() + " ---\n" + safeReply;
                updated.add(new Item(item.id(), item.type(), "waiting_player", item.priority(), item.author(),
                        item.title(), item.body() + addition, item.target(), item.createdAt()));
                changed = true;
            } else updated.add(item);
        }
        return !changed ? "Ticket not found." : save(dataDir, updated) ? "Reply added." : "Reply could not be saved.";
    }

    private static String formCard(String title, String action, String fields) {
        return "<article class='rounded-2xl bg-glass-surface border border-glass-border p-5'><h2 class='text-lg font-bold text-white mb-4'>" + escape(title)
                + "</h2><form method='post' action='/action' class='space-y-3'><input type='hidden' name='action' value='" + action + "'>"
                + fields + "<button class='inline-flex w-full items-center justify-center gap-2 rounded-xl bg-primary px-4 py-2.5 text-sm font-bold text-black hover:brightness-110'>"
                + "<span class='material-symbols-outlined text-[18px]'>add_task</span>Save</button></form></article>";
    }

    private static String input(String name, String label, String placeholder) {
        return "<label class='block'><span class='mb-1 block text-[11px] uppercase tracking-wider text-slate-500'>" + escape(label)
                + "</span><input name='" + name + "' type='text' placeholder='" + escape(placeholder) + "'"
                + ("title".equals(name) ? " required maxlength='160'" : " maxlength='64'") + "></label>";
    }

    private static String textarea(String name, String label, String placeholder) {
        return "<label class='block'><span class='mb-1 block text-[11px] uppercase tracking-wider text-slate-500'>" + escape(label)
                + "</span><textarea name='" + name + "' rows='5' required maxlength='8000' placeholder='" + escape(placeholder) + "'></textarea></label>";
    }

    private static String statusOptions(String current) {
        StringBuilder out = new StringBuilder();
        for (String status : List.of("open", "reviewing", "waiting_player", "resolved", "dismissed")) {
            out.append("<option value='").append(status).append("'")
                    .append(status.equalsIgnoreCase(current == null ? "" : current) ? " selected" : "")
                    .append(">").append(status.substring(0, 1).toUpperCase()).append(status.substring(1)).append("</option>");
        }
        return out.toString();
    }

    private static String select(String name, String label, String[] options) {
        StringBuilder out = new StringBuilder("<label class='block'><span class='mb-1 block text-[11px] uppercase tracking-wider text-slate-500'>")
                .append(escape(label)).append("</span><select name='").append(name).append("'>");
        for (String option : options) {
            out.append("<option value='").append(option).append("'>").append(escape(option)).append("</option>");
        }
        return out.append("</select></label>").toString();
    }

    private static List<Item> load(Path dataDir) {
        List<Item> items = new ArrayList<>();
        try {
            Path file = dataDir.resolve("staff-workflow.txt");
            if (!Files.exists(file)) {
                return items;
            }
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 8L * 1024L * 1024L) {
                return items;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (items.size() >= 2000) break;
                try {
                    String[] p = line.split("\\|", -1);
                    if (p.length >= 9) {
                        items.add(new Item(p[0], p[1], p[2], p[3], p[4], dec(p[5]), dec(p[6]), dec(p[7]), Long.parseLong(p[8])));
                    }
                } catch (Exception ignoredLine) {
                }
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    private static boolean save(Path dataDir, List<Item> items) {
        Path temp = null;
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("staff-workflow.txt");
            temp = dataDir.resolve("staff-workflow.txt.tmp");
            List<String> lines = new ArrayList<>();
            for (Item item : items) {
                if (lines.size() >= 2000) break;
                lines.add(String.join("|", item.id(), item.type(), item.status(), item.priority(), item.author(),
                        enc(item.title()), enc(item.body()), enc(item.target()), String.valueOf(item.createdAt())));
            }
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            FilePermissions.ownerReadWrite(file);
            return true;
        } catch (Exception ignored) {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignoredDelete) { }
            }
            return false;
        }
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record Item(String id, String type, String status, String priority, String author, String title, String body, String target, long createdAt) {
    }
}
