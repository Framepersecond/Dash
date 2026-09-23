package dash.web;

import dash.security.FilePermissions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

public final class PublicReportLinks {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;

    private PublicReportLinks() {
    }

    public static synchronized CreatedLink create(Path dataDir, String createdBy, int lifetimeMinutes,
            String target, String category) {
        int minutes = Math.max(5, Math.min(10_080, lifetimeMinutes));
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        long expiresAt = Instant.now().toEpochMilli() + Math.min(MAX_TTL_MILLIS, minutes * 60_000L);
        Link link = new Link(hash(token), expiresAt, clean(createdBy, 64), clean(target, 64),
                StaffPage.safeCategory(category), 0L);
        List<Link> links = load(dataDir);
        links.removeIf(existing -> existing.usedAt() > 0 || existing.expiresAt() < Instant.now().minusSeconds(86_400).toEpochMilli());
        links.add(0, link);
        if (!save(dataDir, links)) return new CreatedLink("", 0L);
        return new CreatedLink(token, expiresAt);
    }

    public static synchronized Link inspect(Path dataDir, String token) {
        if (token == null || token.length() < 32 || token.length() > 128) return null;
        String wanted = hash(token);
        long now = System.currentTimeMillis();
        for (Link link : load(dataDir)) {
            if (constantEquals(wanted, link.tokenHash()) && link.usedAt() == 0L && link.expiresAt() >= now) return link;
        }
        return null;
    }

    public static synchronized String submit(Path dataDir, String token, String title, String body,
            String category, String target, String reporter, String contact, String honeypot) {
        if (honeypot != null && !honeypot.isBlank()) return "This report link is unavailable.";
        Link valid = inspect(dataDir, token);
        if (valid == null) return "This report link is invalid, expired, or has already been used.";
        String safeReporter = clean(reporter, 64);
        String safeContact = clean(contact, 160);
        String details = body == null ? "" : body.trim();
        if (!safeContact.isBlank()) details += "\n\nReply contact: " + safeContact;
        String result = StaffPage.createDetailed(dataDir, title, details, "normal",
                valid.target().isBlank() ? target : valid.target(),
                safeReporter.isBlank() ? "Public report link" : safeReporter,
                category == null || category.isBlank() ? valid.category() : category);
        if (!result.toLowerCase().contains("created")) return result;

        long now = System.currentTimeMillis();
        List<Link> links = load(dataDir);
        List<Link> updated = new ArrayList<>(links.size());
        for (Link link : links) {
            updated.add(constantEquals(valid.tokenHash(), link.tokenHash())
                    ? new Link(link.tokenHash(), link.expiresAt(), link.createdBy(), link.target(), link.category(), now)
                    : link);
        }
        if (!save(dataDir, updated)) return "Report created, but the one-time link could not be closed. Contact staff.";
        return "Report submitted. This one-time link is now closed.";
    }

    public static String render(Path dataDir, String token, String message) {
        Link link = inspect(dataDir, token);
        boolean available = link != null;
        String notice = message == null || message.isBlank() ? "" :
                "<div class='notice' role='alert'><span>!</span><p>" + esc(message) + "</p></div>";
        boolean submitted = message != null && message.startsWith("Report submitted");
        String form = submitted
                ? "<section class='state-panel'><div class='state-icon success'>✓</div><p class='eyebrow'>Report received</p><h1>Thank you</h1><p>Your report is now in the private staff inbox. This one-time link has been closed.</p><div class='state-meta'>No dashboard account was created or required.</div></section>"
                : !available
                ? "<section class='state-panel'><div class='state-icon unavailable'>×</div><p class='eyebrow'>Secure report</p><h1>Link unavailable</h1><p>This one-time link is invalid, expired, or has already been used.</p><div class='state-meta'>Request a new link from the server team or use <b>/dash report</b> in-game.</div></section>"
                : "<section class='report-panel'><div class='panel-head'><div><p class='eyebrow'>Reports</p><h1>Submit a report</h1><p>Send relevant details directly to the private staff inbox.</p></div><span class='status'><i></i>One-time link</span></div>"
                + notice + "<form method='post' action='/report' id='report-form'><input type='hidden' name='token' value='" + esc(token) + "'><div class='form-grid'>"
                + "<label><span>Your Minecraft name</span><input name='reporter' maxlength='64' autocomplete='nickname' placeholder='Player name'></label>"
                + "<label><span>Category</span><select name='category'>" + StaffPage.categoryOptions(link.category()) + "</select></label>"
                + "<label class='wide'><span>Short title</span><input name='title' required maxlength='160' placeholder='Briefly describe what happened'></label>"
                + (link.target().isBlank() ? "<label><span>Player involved <small>Optional</small></span><input name='target_player' maxlength='64' placeholder='Player name'></label>"
                    : "<input type='hidden' name='target_player' value='" + esc(link.target()) + "'><div class='bound'><span>Linked player</span><b>" + esc(link.target()) + "</b></div>")
                + "<label><span>Reply contact <small>Optional</small></span><input name='contact' maxlength='160' placeholder='Discord or email'></label>"
                + "<label class='wide'><span>Details</span><textarea name='body' required minlength='8' maxlength='8000' rows='7' placeholder='Include the time, place, people involved, and anything staff should verify.'></textarea><small class='hint'>Minimum 8 characters. Avoid unrelated personal information.</small></label>"
                + "<label class='trap' aria-hidden='true'>Website <input name='website' tabindex='-1' autocomplete='off'></label>"
                + "</div><div class='form-footer'><div class='privacy'><b>Private submission</b><span>Never include passwords, access tokens or private addresses.</span></div><button id='submit-report'><span class='button-label'>Submit report</span><span class='spinner' aria-hidden='true'></span></button></div></form></section>";
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Secure report - Dash</title>"
                + "<style>:root{color-scheme:dark;--bg:#080914;--surface:#0d1220;--surface-2:#111827;--line:rgba(100,116,139,.3);--muted:#7f8da3;--text:#e8edf7;--cyan:#22d3ee}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:var(--bg);color:var(--text);font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}.shell{width:min(100%,960px);margin:0 auto;padding:0 22px 48px}.topbar{height:68px;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid rgba(100,116,139,.2)}.brand{display:flex;align-items:center;gap:10px;font-size:15px;font-weight:800;color:#f8fafc}.brand-mark{display:grid;place-items:center;width:30px;height:30px;border:1px solid rgba(34,211,238,.4);border-radius:6px;background:rgba(8,145,178,.14);color:#67e8f9;font-size:13px}.secure{display:flex;align-items:center;gap:7px;color:#94a3b8;font-size:11px}.secure i,.status i{width:7px;height:7px;border-radius:50%;background:#10b981;box-shadow:0 0 0 3px rgba(16,185,129,.12)}main{padding-top:34px}.page-title{margin-bottom:20px}.page-title .eyebrow{margin-bottom:7px}.page-title h2{margin:0;color:#f8fafc;font-size:22px;letter-spacing:0}.page-title p{margin:7px 0 0;color:var(--muted);font-size:13px}.report-panel,.state-panel{border:1px solid var(--line);border-radius:8px;background:var(--surface);box-shadow:0 18px 50px rgba(0,0,0,.28);overflow:hidden;animation:enter .42s cubic-bezier(.16,1,.3,1) both}.panel-head{display:flex;align-items:flex-start;justify-content:space-between;gap:22px;padding:22px 24px;border-bottom:1px solid rgba(100,116,139,.2)}.eyebrow{margin:0;color:#67e8f9;font-size:10px;font-weight:900;letter-spacing:.08em;text-transform:uppercase}.panel-head h1,.state-panel h1{margin:6px 0 0;color:#f8fafc;font-size:21px;letter-spacing:0}.panel-head p,.state-panel>p{margin:6px 0 0;color:var(--muted);font-size:12px;line-height:1.55}.status{display:flex;align-items:center;gap:8px;flex:0 0 auto;border:1px solid rgba(100,116,139,.3);border-radius:999px;padding:7px 10px;color:#cbd5e1;background:rgba(2,6,23,.35);font-size:10px;font-weight:800}.notice{display:flex;align-items:flex-start;gap:10px;margin:16px 24px 0;border:1px solid rgba(251,113,133,.35);border-radius:7px;background:rgba(244,63,94,.08);padding:11px 12px;color:#fecdd3;font-size:12px}.notice>span{display:grid;place-items:center;width:18px;height:18px;border-radius:50%;background:rgba(244,63,94,.18);font-weight:900}.notice p{margin:1px 0 0}form{padding:22px 24px 24px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:17px 18px}label{display:grid;align-content:start;gap:7px;color:#cbd5e1;font-size:11px;font-weight:800}label>span{display:flex;justify-content:space-between;gap:8px}label small{color:#64748b;font-size:10px;font-weight:600}.wide{grid-column:1/-1}input,select,textarea{width:100%;min-width:0;border:1px solid rgba(100,116,139,.42);border-radius:6px;background:#080d19;padding:11px 12px;color:#e2e8f0;font:inherit;font-size:13px;line-height:1.35;outline:none;transition:border-color .18s ease,box-shadow .18s ease,background .18s ease}input::placeholder,textarea::placeholder{color:#526077}select{color-scheme:dark}textarea{min-height:142px;resize:vertical}input:hover,select:hover,textarea:hover{border-color:rgba(148,163,184,.48)}input:focus,select:focus,textarea:focus{border-color:rgba(34,211,238,.72);background:#090f1d;box-shadow:0 0 0 3px rgba(34,211,238,.1)}.hint{color:#64748b;font-size:10px;font-weight:500}.bound{display:grid;align-content:center;gap:7px;min-height:65px;border:1px solid rgba(34,211,238,.25);border-radius:6px;background:rgba(8,145,178,.08);padding:10px 12px}.bound span{color:#7f8da3;font-size:10px;font-weight:800;text-transform:uppercase}.bound b{color:#a5f3fc;font-size:13px}.form-footer{display:flex;align-items:center;justify-content:space-between;gap:22px;margin-top:22px;padding-top:18px;border-top:1px solid rgba(100,116,139,.2)}.privacy{display:grid;gap:3px;font-size:10px}.privacy b{color:#cbd5e1}.privacy span{color:#64748b}button{display:inline-flex;align-items:center;justify-content:center;gap:9px;min-width:150px;min-height:39px;border:1px solid rgba(34,211,238,.5);border-radius:6px;background:rgba(8,145,178,.14);padding:0 16px;color:#67e8f9;font-size:11px;font-weight:900;cursor:pointer;transition:background .18s ease,border-color .18s ease,transform .18s ease}button:hover{border-color:#22d3ee;background:rgba(8,145,178,.24)}button:active{transform:translateY(1px)}button:disabled{cursor:wait;opacity:.7}.spinner{display:none;width:13px;height:13px;border:2px solid rgba(103,232,249,.3);border-top-color:#67e8f9;border-radius:50%;animation:spin .7s linear infinite}button.is-loading .spinner{display:block}.state-panel{max-width:620px;margin:46px auto 0;padding:32px}.state-icon{display:grid;place-items:center;width:38px;height:38px;margin-bottom:18px;border-radius:7px;font-weight:900}.state-icon.success{border:1px solid rgba(16,185,129,.35);background:rgba(16,185,129,.12);color:#6ee7b7}.state-icon.unavailable{border:1px solid rgba(251,113,133,.35);background:rgba(244,63,94,.1);color:#fda4af}.state-meta{margin-top:22px;border-top:1px solid rgba(100,116,139,.2);padding-top:16px;color:#64748b;font-size:11px}.trap{position:absolute;left:-10000px}@keyframes enter{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:640px){.shell{padding:0 14px 30px}.topbar{height:58px}.secure{font-size:0}.secure:after{content:'Private';font-size:10px}.page-title{margin-bottom:15px}main{padding-top:23px}.panel-head{padding:18px;flex-direction:column}.form-grid{grid-template-columns:1fr}.wide{grid-column:auto}form{padding:18px}.notice{margin:14px 18px 0}.form-footer{align-items:stretch;flex-direction:column}button{width:100%}.state-panel{margin-top:28px;padding:24px}}@media(prefers-reduced-motion:reduce){*,*:before,*:after{animation-duration:.01ms!important;transition-duration:.01ms!important}}</style></head><body><div class='shell'><header class='topbar'><div class='brand'><span class='brand-mark'>D</span><span>Dash</span></div><span class='secure'><i></i>Secure server report</span></header><main><div class='page-title'><p class='eyebrow'>Player support</p><h2>Contact the server team</h2><p>No account or dashboard access is required.</p></div>"
                + form + "</main></div><script>(function(){var form=document.getElementById('report-form');if(!form)return;form.addEventListener('submit',function(){var button=document.getElementById('submit-report');if(!button)return;button.disabled=true;button.classList.add('is-loading');button.querySelector('.button-label').textContent='Submitting';});})();</script></body></html>";
    }

    private static List<Link> load(Path dataDir) {
        List<Link> out = new ArrayList<>();
        if (dataDir == null) return out;
        Path file = dataDir.resolve("public-report-links.txt");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                    || Files.size(file) > 2L * 1024L * 1024L) return out;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", -1);
                if (p.length >= 6) out.add(new Link(p[0], Long.parseLong(p[1]), dec(p[2]), dec(p[3]), dec(p[4]), Long.parseLong(p[5])));
                if (out.size() >= 1000) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean save(Path dataDir, List<Link> links) {
        if (dataDir == null) return false;
        Path temp = null;
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("public-report-links.txt");
            temp = dataDir.resolve("public-report-links.txt.tmp");
            List<String> lines = links.stream().limit(1000)
                    .map(link -> String.join("|", link.tokenHash(), Long.toString(link.expiresAt()), enc(link.createdBy()),
                            enc(link.target()), enc(link.category()), Long.toString(link.usedAt())))
                    .toList();
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            FilePermissions.ownerReadWrite(file);
            return true;
        } catch (Exception ignored) {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignoredDelete) { }
            return false;
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(clean(value, 256).getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record CreatedLink(String token, long expiresAt) {
        public boolean success() { return token != null && !token.isBlank(); }
    }

    public record Link(String tokenHash, long expiresAt, String createdBy, String target, String category, long usedAt) {
    }
}
