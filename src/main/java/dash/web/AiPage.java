package dash.web;

import dash.ai.AiAgentManager;
import dash.ai.AiTerms;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class AiPage {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private AiPage() {
    }

    public static String render(AiAgentManager manager, String username, String role, boolean canConfigure,
            boolean canAudit, String rawConversation, String message, boolean forceSetup) {
        AiAgentManager.ConfigStatus status = manager.status(username);
        String conversationId = rawConversation == null ? "" : rawConversation.trim();
        List<AiAgentManager.Conversation> conversations = manager.conversations(username, 40);
        if (conversationId.isBlank() && !conversations.isEmpty()) conversationId = conversations.get(0).id();
        List<AiAgentManager.Message> messages = manager.messages(username, conversationId, 200);
        List<AiAgentManager.Proposal> proposals = manager.proposals(username, canAudit, 40);
        String banner = message == null || message.isBlank() ? ""
                : "<div class='ai-banner' role='status'><span class='material-symbols-outlined'>info</span><span>"
                        + esc(message) + "</span></div>";
        String content = HtmlTemplate.statsHeader() + styles()
                + "<main class='ai-main flex-1 min-w-0 p-4 sm:p-6'><div class='max-w-7xl mx-auto space-y-4'>"
                + header(status) + banner
                + (!status.enabled() || (forceSetup && canConfigure) ? setup(status, canConfigure, username, role)
                        : !status.userAccepted() ? userConsent(username, role)
                        : workspace(status, conversations, conversationId, messages, proposals, canConfigure))
                + "</div></main>" + script() + HtmlTemplate.statsScript();
        return HtmlTemplate.page("Dash AI", "/ai", content);
    }

    private static String header(AiAgentManager.ConfigStatus status) {
        String state = status.enabled() ? "Enabled" : "Disabled";
        return "<header class='ai-hero'><div><div class='ai-kicker'><span class='material-symbols-outlined'>auto_awesome</span>Dash AI</div>"
                + "<h1>Server intelligence with guarded actions</h1><p>Analyze evidence, prepare changes and keep every mutation behind a human approval.</p></div>"
                + "<div class='ai-status " + (status.enabled() ? "is-on" : "is-off") + "'><i></i><span><small>Provider</small><b>"
                + esc(state) + "</b></span></div></header>";
    }

    private static String setup(AiAgentManager.ConfigStatus status, boolean canConfigure, String username, String role) {
        String ownerTerms = status.ownerAccepted() ? accepted("Owner terms accepted")
                : canConfigure ? termsForm(true, username, role) : notice("The Main Admin must accept the owner terms.");
        String key = status.keyConfigured()
                ? accepted(status.externalKey() ? "API key managed by environment" : "Encrypted API key ending " + status.keyFingerprint())
                : notice("No Google API key configured.");
        String configuration = !canConfigure ? notice("Only the Main Admin can configure Dash AI.")
                : "<form id='ai-config-form' method='post' action='/api/ai/config' class='ai-form-grid'>"
                        + "<label><span>Google API key</span><input type='password' name='api_key' autocomplete='off' placeholder='Leave blank to keep current key'></label>"
                        + "<label><span>Model</span><select name='model'>" + modelOptions(status.model()) + "</select></label>"
                        + "<div class='ai-form-heading'><b>Generation</b><small>Controls cost, latency and response behavior.</small></div>"
                        + "<label><span>Thinking level</span><select name='thinking_level'>" + option("low", "Low / fastest", status.thinkingLevel()) + option("medium", "Medium / balanced", status.thinkingLevel()) + option("high", "High / deepest", status.thinkingLevel()) + "</select></label>"
                        + "<label><span>Maximum output tokens</span><input type='number' name='max_output_tokens' min='256' max='16384' step='256' value='" + status.maxOutputTokens() + "'></label>"
                        + "<label><span>Deterministic seed (optional)</span><input type='number' name='seed' value='" + esc(status.seed()) + "' placeholder='Random'></label>"
                        + "<label><span>Thinking summaries</span><select name='thinking_summaries'>" + option("none", "Hidden", status.thinkingSummaries()) + option("auto", "Automatic summary", status.thinkingSummaries()) + "</select></label>"
                        + "<label><span>Tool choice</span><select name='tool_choice'>" + option("auto", "Auto", status.toolChoice()) + option("validated", "Validated calls only", status.toolChoice()) + option("none", "No tools / advisory only", status.toolChoice()) + option("any", "Require a tool when possible", status.toolChoice()) + "</select></label>"
                        + "<label><span>Stop sequences (one per line, max 5)</span><textarea name='stop_sequences' rows='3' maxlength='405' placeholder='Optional'>" + esc(status.stopSequences()) + "</textarea></label>"
                        + "<div class='ai-form-heading'><b>Quota guard</b><small>Local ceilings apply before Google is contacted. Defaults preserve a 20 RPD allowance.</small></div>"
                        + "<label><span>Requests per minute</span><input type='number' name='requests_per_minute' min='1' max='1000' value='" + status.requestsPerMinute() + "'></label>"
                        + "<label><span>Requests per rolling day</span><input type='number' name='requests_per_day' min='1' max='100000' value='" + status.requestsPerDay() + "'></label>"
                        + "<label><span>Estimated input tokens per minute</span><input type='number' name='input_tokens_per_minute' min='1000' max='10000000' step='1000' value='" + status.inputTokensPerMinute() + "'></label>"
                        + "<label><span>Provider calls per message</span><input type='number' name='max_provider_calls' min='1' max='6' value='" + status.maxProviderCalls() + "'></label>"
                        + "<label><span>Automatic retries</span><select name='retry_count'>" + option("0", "0 / preserve quota", String.valueOf(status.retryCount())) + option("1", "1 retry", String.valueOf(status.retryCount())) + option("2", "2 retries", String.valueOf(status.retryCount())) + "</select></label>"
                        + "<label class='ai-check'><input type='checkbox' name='enabled' value='true' " + (status.enabled() ? "checked" : "") + "><span>Enable provider calls after saving</span></label>"
                        + "<label class='ai-check'><input type='checkbox' name='agentic_enabled' value='true' " + (status.agenticEnabled() ? "checked" : "") + "><span>Allow guarded agentic proposals</span></label>"
                        + "<div class='ai-button-row'><button class='ai-button primary' type='submit'><span class='material-symbols-outlined'>lock</span>Save securely</button>"
                        + "<button class='ai-button' name='operation' value='test' type='submit'><span class='material-symbols-outlined'>network_check</span>Test key</button>"
                        + (status.keyConfigured() && !status.externalKey() ? "<button class='ai-button danger' name='operation' value='remove_key' type='submit'><span class='material-symbols-outlined'>key_off</span>Remove stored key</button>" : "")
                        + "<span id='ai-config-state' role='status' aria-live='polite'></span></div></form>";
        return "<section class='ai-setup-grid'>" + panel("Owner consent", "verified_user", ownerTerms)
                + panel("Provider key", "key", key) + panelWide("Configuration", "tune", configuration)
                + panelWide("Privacy boundary", "shield_lock",
                        "<p class='ai-copy'>The key stays on the server. Dash sends only the prompt and context you deliberately attach after credential, token, email and IP redaction. Disabled means no provider calls and no background AI work.</p>")
                + "</section>";
    }

    private static String userConsent(String username, String role) {
        return "<section class='ai-consent'><div class='ai-consent-head'><span class='material-symbols-outlined'>gavel</span><div><h2>Review before first use</h2>"
                + "<p>Your consent is separate from the server owner's activation.</p></div></div>" + terms()
                + termsForm(false, username, role) + "</section>";
    }

    private static String workspace(AiAgentManager.ConfigStatus status,
            List<AiAgentManager.Conversation> conversations, String selected,
            List<AiAgentManager.Message> messages, List<AiAgentManager.Proposal> proposals,
            boolean canConfigure) {
        StringBuilder history = new StringBuilder("<div class='ai-history-head'><b>Conversations</b><button id='ai-new' class='ai-icon' title='New conversation'><span class='material-symbols-outlined'>add</span></button></div><div class='ai-history-list'>");
        for (AiAgentManager.Conversation row : conversations) {
            history.append("<a href='/ai?conversation=").append(url(row.id())).append("' class='")
                    .append(row.id().equals(selected) ? "is-active" : "").append("'><span class='material-symbols-outlined'>chat</span><span><b>")
                    .append(esc(row.title())).append("</b><small>").append(esc(row.mode())).append(" / ")
                    .append(TIME.format(Instant.ofEpochMilli(row.updatedAt()))).append("</small></span></a>");
        }
        if (conversations.isEmpty()) history.append("<div class='ai-empty'>Start your first analysis.</div>");
        history.append("</div>");

        StringBuilder chat = new StringBuilder("<div id='ai-messages' class='ai-messages'>");
        for (AiAgentManager.Message row : messages) {
            chat.append("<article class='ai-message ").append("user".equals(row.role()) ? "is-user" : "is-assistant")
                    .append("'><div><b>").append("user".equals(row.role()) ? "You" : "Dash AI").append("</b><time>")
                    .append(TIME.format(Instant.ofEpochMilli(row.createdAt()))).append("</time></div><p>")
                    .append(esc(row.content())).append("</p></article>");
        }
        if (messages.isEmpty()) chat.append("<div class='ai-empty ai-empty-chat'><span class='material-symbols-outlined'>neurology</span><b>Ask about server health, failures or a planned change.</b><small>Read tools run automatically. Changes always wait for approval.</small></div>");
        chat.append("</div>");

        String composer = "<form id='ai-composer' class='ai-composer'><input type='hidden' name='conversation_id' value='" + esc(selected) + "'>"
                + "<div class='ai-context'><label><input type='checkbox' name='context' value='health' checked>Health</label>"
                + "<label><input type='checkbox' name='context' value='players'>Players</label><label><input type='checkbox' name='context' value='logs'>Recent logs</label><label><input type='checkbox' name='context' value='plugins'>Plugins</label></div>"
                + "<textarea name='prompt' maxlength='12000' required placeholder='Ask Dash AI...'></textarea><div class='ai-composer-actions'>"
                + "<label class='ai-check'><input type='checkbox' name='agentic' value='true' " + (!status.agenticEnabled() ? "disabled" : "") + "><span>Agentic proposals</span></label>"
                + "<span id='ai-state'>Ready</span><button id='ai-send' class='ai-button primary' type='submit'><span class='material-symbols-outlined'>arrow_upward</span>Send</button>"
                + "<button id='ai-cancel' class='ai-icon hidden' type='button' title='Cancel'><span class='material-symbols-outlined'>stop</span></button></div></form>";

        String actions = proposals(proposals);
        String settings = canConfigure ? "<a class='ai-button' href='/ai?setup=1'><span class='material-symbols-outlined'>settings</span>Provider settings</a>" : "";
        return "<nav class='ai-tabs'><button class='is-active' data-ai-view='chat'>Assistant</button><button data-ai-view='actions'>Approvals <span>"
                + proposals.stream().filter(p -> "pending".equals(p.status())).count() + "</span></button></nav>"
                + "<section class='ai-workspace' data-ai-panel='chat'><aside class='ai-history'>" + history + "</aside><div class='ai-chat'>"
                + "<div class='ai-chat-head'><div><b>" + esc(status.model()) + "</b><small>Google Gemini / server-side key</small></div>" + settings + "</div>"
                + chat + composer + "</div></section><section class='ai-actions hidden' data-ai-panel='actions'>" + actions + "</section>";
    }

    private static String proposals(List<AiAgentManager.Proposal> proposals) {
        StringBuilder out = new StringBuilder("<div class='ai-table'><div class='ai-table-head'><span>Action</span><span>Risk</span><span>Status</span><span>Requested</span><span></span></div>");
        for (AiAgentManager.Proposal proposal : proposals) {
            boolean pending = "pending".equals(proposal.status()) && proposal.expiresAt() > System.currentTimeMillis();
            String controls = pending ? "<form method='post' action='/api/ai/proposal' class='ai-proposal-form' data-risk='" + esc(proposal.risk())
                    + "' data-tool='" + esc(proposal.tool()) + "'><input type='hidden' name='proposal_id' value='" + esc(proposal.id())
                    + "'><input type='text' name='reason' maxlength='500' required placeholder='Approval reason'><button class='ai-button primary' name='decision' value='approve'>Approve</button>"
                    + "<button class='ai-button danger' name='decision' value='reject'>Reject</button></form>" : "";
            out.append("<div class='ai-table-row'><span><b>").append(esc(human(proposal.tool()))).append("</b><small>")
                    .append(esc(proposal.argsJson())).append("</small></span><span class='ai-pill risk-").append(esc(proposal.risk())).append("'>")
                    .append(esc(proposal.risk())).append("</span><span class='ai-pill status-").append(esc(proposal.status())).append("'>")
                    .append(esc(proposal.status())).append("</span><span>").append(TIME.format(Instant.ofEpochMilli(proposal.createdAt())))
                    .append("</span><span>").append(controls).append("</span></div>");
        }
        if (proposals.isEmpty()) out.append("<div class='ai-empty'>No agentic proposals.</div>");
        return out.append("</div>").toString();
    }

    private static String termsForm(boolean owner, String username, String role) {
        return "<form method='post' action='/api/ai/consent' class='ai-terms-form'>"
                + "<input type='hidden' name='owner' value='" + owner + "'><input type='hidden' name='username' value='" + esc(username)
                + "'><input type='hidden' name='role' value='" + esc(role) + "'><label class='ai-check'><input type='checkbox' name='agree' value='true' required>"
                + "<span>I have read and accept " + esc(AiTerms.TITLE) + ".</span></label><button class='ai-button primary' type='submit'>Accept terms</button></form>";
    }

    private static String terms() {
        StringBuilder out = new StringBuilder("<ol class='ai-terms'>");
        for (String clause : AiTerms.CLAUSES) out.append("<li>").append(esc(clause)).append("</li>");
        return out.append("</ol>").toString();
    }

    private static String modelOptions(String selected) {
        return option("gemini-3.7-flash", "Gemini 3.7 Flash", selected)
                + option("gemini-2.5-pro", "Gemini 2.5 Pro", selected);
    }

    private static String option(String value, String label, String selected) {
        return "<option value='" + value + "'" + (value.equals(selected) ? " selected" : "") + ">" + label + "</option>";
    }

    private static String panel(String title, String icon, String body) { return "<article class='ai-panel'><h2><span class='material-symbols-outlined'>" + icon + "</span>" + esc(title) + "</h2>" + body + "</article>"; }
    private static String panelWide(String title, String icon, String body) { return "<article class='ai-panel ai-panel-wide'><h2><span class='material-symbols-outlined'>" + icon + "</span>" + esc(title) + "</h2>" + body + "</article>"; }
    private static String accepted(String text) { return "<div class='ai-verdict good'><span class='material-symbols-outlined'>check_circle</span><span>" + esc(text) + "</span></div>"; }
    private static String notice(String text) { return "<div class='ai-verdict watch'><span class='material-symbols-outlined'>info</span><span>" + esc(text) + "</span></div>"; }

    private static String styles() {
        return "<style>"
                + ".ai-main{color:#cbd5e1}.ai-hero{display:flex;align-items:flex-end;justify-content:space-between;gap:1rem;border-bottom:1px solid rgba(100,116,139,.24);padding:1rem 0 1.1rem}.ai-kicker{display:flex;align-items:center;gap:.4rem;color:#67e8f9;font-size:.68rem;font-weight:800;text-transform:uppercase}.ai-hero h1{margin:.35rem 0 0;font-size:1.55rem;color:#f8fafc}.ai-hero p{margin:.28rem 0 0;color:#64748b;font-size:.76rem}.ai-status{display:flex;align-items:center;gap:.55rem;border:1px solid rgba(100,116,139,.28);border-radius:7px;padding:.55rem .7rem;min-width:130px}.ai-status i{width:8px;height:8px;border-radius:50%;background:#64748b}.ai-status.is-on i{background:#34d399;box-shadow:0 0 0 4px rgba(52,211,153,.12)}.ai-status small,.ai-status b{display:block}.ai-status small{font-size:.56rem;color:#64748b}.ai-status b{font-size:.72rem;color:#e2e8f0}.ai-banner{display:flex;gap:.5rem;border-left:3px solid #22d3ee;background:rgba(8,145,178,.08);border-radius:5px;padding:.7rem;font-size:.72rem}"
                + ".ai-setup-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem}.ai-panel,.ai-consent,.ai-actions{border:1px solid rgba(100,116,139,.22);border-radius:7px;background:rgba(2,6,23,.32);padding:1rem}.ai-panel-wide{grid-column:1/-1}.ai-panel h2{display:flex;align-items:center;gap:.45rem;margin:0 0 .8rem;font-size:.82rem;color:#e2e8f0}.ai-panel h2 span{color:#22d3ee;font-size:18px}.ai-copy{font-size:.72rem;line-height:1.55;color:#94a3b8}.ai-verdict{display:flex;align-items:center;gap:.5rem;border-left:3px solid #fbbf24;background:rgba(245,158,11,.07);border-radius:5px;padding:.65rem;font-size:.7rem}.ai-verdict.good{border-color:#34d399;background:rgba(16,185,129,.07)}.ai-verdict span:first-child{font-size:18px}.ai-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:.75rem}.ai-form-grid label>span,.ai-composer span{font-size:.62rem;color:#94a3b8}.ai-form-grid input,.ai-form-grid select,.ai-composer textarea,.ai-proposal-form input{width:100%;margin-top:.3rem;border:1px solid rgba(100,116,139,.3)!important;border-radius:6px!important;background:rgba(2,6,23,.75)!important;padding:.65rem!important;color:#e2e8f0!important}.ai-check{display:flex!important;align-items:center;gap:.45rem}.ai-check input{width:auto!important;margin:0!important}.ai-button-row{grid-column:1/-1;display:flex;flex-wrap:wrap;gap:.45rem}.ai-button,.ai-icon{display:inline-flex;align-items:center;justify-content:center;gap:.35rem;border:1px solid rgba(100,116,139,.34);border-radius:6px;padding:.48rem .65rem;font-size:.66rem;font-weight:800;color:#cbd5e1;background:rgba(15,23,42,.7)}.ai-button.primary{border-color:rgba(34,211,238,.36);color:#67e8f9;background:rgba(8,145,178,.12)}.ai-button.danger{border-color:rgba(244,63,94,.3);color:#fda4af;background:rgba(244,63,94,.08)}.ai-button span{font-size:16px}.ai-icon{width:32px;height:32px;padding:0}.ai-consent{max-width:850px;margin:0 auto}.ai-consent-head{display:flex;gap:.7rem}.ai-consent-head h2{margin:0;color:#f8fafc}.ai-consent-head p{margin:.2rem 0 0;font-size:.72rem;color:#64748b}.ai-terms{display:grid;gap:.45rem;margin:1rem 0;padding-left:1.2rem}.ai-terms li{font-size:.7rem;line-height:1.5;color:#94a3b8}.ai-terms-form{display:flex;align-items:center;justify-content:space-between;gap:.7rem;border-top:1px solid rgba(100,116,139,.2);padding-top:.8rem}"
                + ".ai-tabs{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));border:1px solid rgba(100,116,139,.22);border-radius:7px;overflow:hidden}.ai-tabs button{border:0;border-right:1px solid rgba(100,116,139,.2);padding:.65rem;background:rgba(2,6,23,.3);font-size:.68rem;font-weight:800;color:#64748b}.ai-tabs button.is-active{background:rgba(8,145,178,.11);color:#67e8f9}.ai-tabs span{display:inline-flex;margin-left:.3rem;border-radius:99px;background:rgba(34,211,238,.12);padding:.08rem .35rem}.ai-workspace{display:grid;grid-template-columns:245px minmax(0,1fr);min-height:640px;border:1px solid rgba(100,116,139,.22);border-radius:7px;overflow:hidden;background:rgba(2,6,23,.25)}.ai-history{border-right:1px solid rgba(100,116,139,.2);padding:.65rem}.ai-history-head,.ai-chat-head{display:flex;align-items:center;justify-content:space-between;gap:.5rem;padding:.35rem}.ai-history-head b{font-size:.68rem;color:#94a3b8}.ai-history-list{display:grid;gap:.25rem;margin-top:.4rem}.ai-history-list a{display:flex;gap:.45rem;border-radius:5px;padding:.55rem;color:#64748b}.ai-history-list a.is-active{background:rgba(8,145,178,.1);color:#67e8f9}.ai-history-list a>span:first-child{font-size:17px}.ai-history-list b,.ai-history-list small{display:block}.ai-history-list b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:170px;font-size:.66rem}.ai-history-list small{margin-top:.15rem;font-size:.55rem;color:#64748b}.ai-chat{display:grid;grid-template-rows:auto minmax(0,1fr) auto;min-width:0}.ai-chat-head{border-bottom:1px solid rgba(100,116,139,.18);padding:.65rem .8rem}.ai-chat-head b,.ai-chat-head small{display:block}.ai-chat-head b{font-size:.72rem;color:#e2e8f0}.ai-chat-head small{font-size:.57rem;color:#64748b}.ai-messages{display:flex;flex-direction:column;gap:.7rem;overflow:auto;padding:1rem;max-height:560px}.ai-message{max-width:82%;border:1px solid rgba(100,116,139,.2);border-radius:7px;padding:.7rem;background:rgba(15,23,42,.5)}.ai-message.is-user{align-self:flex-end;border-color:rgba(34,211,238,.22);background:rgba(8,145,178,.08)}.ai-message>div{display:flex;justify-content:space-between;gap:.8rem}.ai-message b{font-size:.62rem;color:#67e8f9}.ai-message time{font-size:.54rem;color:#475569}.ai-message p{margin:.35rem 0 0;white-space:pre-wrap;font-size:.72rem;line-height:1.55;color:#cbd5e1}.ai-composer{border-top:1px solid rgba(100,116,139,.2);padding:.7rem}.ai-context{display:flex;gap:.6rem;margin-bottom:.45rem}.ai-context label{display:flex;align-items:center;gap:.25rem;font-size:.58rem;color:#64748b}.ai-composer textarea{min-height:82px;resize:vertical;margin:0!important}.ai-composer-actions{display:flex;align-items:center;gap:.55rem;margin-top:.45rem}.ai-composer-actions #ai-state{margin-left:auto}.ai-empty{padding:1rem;text-align:center;font-size:.67rem;color:#64748b}.ai-empty-chat{display:grid;place-items:center;margin:auto}.ai-empty-chat>span{font-size:34px;color:#164e63}.ai-empty-chat b,.ai-empty-chat small{display:block;margin-top:.3rem}.ai-table{overflow:auto}.ai-table-head,.ai-table-row{display:grid;grid-template-columns:minmax(220px,1.4fr) 80px 90px 125px minmax(220px,1fr);gap:.65rem;align-items:center;min-width:820px;padding:.6rem}.ai-table-head{font-size:.58rem;font-weight:800;text-transform:uppercase;color:#64748b}.ai-table-row{border-top:1px solid rgba(100,116,139,.17);font-size:.65rem}.ai-table-row b,.ai-table-row small{display:block}.ai-table-row b{color:#e2e8f0}.ai-table-row small{margin-top:.2rem;max-width:320px;overflow:hidden;text-overflow:ellipsis;color:#64748b}.ai-pill{width:max-content;border:1px solid rgba(100,116,139,.3);border-radius:99px;padding:.15rem .4rem;font-size:.55rem;font-weight:800}.risk-high,.status-failed,.status-rejected{color:#fda4af;border-color:rgba(244,63,94,.3)}.risk-moderate,.status-pending{color:#fcd34d;border-color:rgba(245,158,11,.3)}.status-executed,.status-approved{color:#6ee7b7;border-color:rgba(16,185,129,.3)}.ai-proposal-form{display:grid;grid-template-columns:1fr auto auto;gap:.35rem}.ai-proposal-form input{margin:0!important;padding:.45rem!important}.hidden{display:none!important}"
                + ".ai-form-grid textarea{width:100%;margin-top:.3rem;border:1px solid rgba(100,116,139,.3);border-radius:6px;background:rgba(2,6,23,.75);padding:.65rem;color:#e2e8f0;resize:vertical}.ai-form-heading{grid-column:1/-1;border-bottom:1px solid rgba(100,116,139,.2);padding:.45rem 0 .35rem}.ai-form-heading b,.ai-form-heading small{display:block}.ai-form-heading b{font-size:.72rem;color:#e2e8f0}.ai-form-heading small{margin-top:.15rem;font-size:.58rem;color:#64748b}"
                + "@media(max-width:760px){.ai-hero{align-items:flex-start;flex-direction:column}.ai-setup-grid,.ai-form-grid{grid-template-columns:1fr}.ai-workspace{grid-template-columns:1fr}.ai-history{border-right:0;border-bottom:1px solid rgba(100,116,139,.2);max-height:180px;overflow:auto}.ai-message{max-width:95%}.ai-terms-form{align-items:flex-start;flex-direction:column}.ai-proposal-form{grid-template-columns:1fr}.ai-status{width:100%}}@media(prefers-reduced-motion:reduce){.ai-main *{scroll-behavior:auto!important;transition:none!important;animation:none!important}}"
                + "</style>";
    }

    private static String script() {
        return "<script>(function(){"
                + "const tabs=document.querySelectorAll('[data-ai-view]');tabs.forEach(b=>b.addEventListener('click',()=>{tabs.forEach(x=>x.classList.toggle('is-active',x===b));document.querySelectorAll('[data-ai-panel]').forEach(p=>p.classList.toggle('hidden',p.dataset.aiPanel!==b.dataset.aiView));}));"
                + "const n=document.getElementById('ai-new');if(n)n.addEventListener('click',()=>location.href='/ai?new=1');"
                + "document.querySelectorAll('.ai-proposal-form').forEach(f=>f.addEventListener('submit',e=>{const submit=e.submitter;if(!submit||submit.value!=='approve')return;if(f.dataset.risk==='high'){const expected='APPROVE '+f.dataset.tool;const typed=prompt('Type '+expected+' to approve this high-risk action.');if(typed!==expected){e.preventDefault();return;}const i=document.createElement('input');i.type='hidden';i.name='confirmation';i.value=typed;f.appendChild(i);}}));"
                + "const config=document.getElementById('ai-config-form');if(config)config.addEventListener('submit',async e=>{e.preventDefault();const submit=e.submitter,buttons=Array.from(config.querySelectorAll('button')),state=document.getElementById('ai-config-state'),body=new URLSearchParams(new FormData(config)),ctl=new AbortController(),timeout=setTimeout(()=>ctl.abort(),15000);if(submit&&submit.name)body.set(submit.name,submit.value||'');buttons.forEach(b=>b.disabled=true);if(state)state.textContent=submit&&submit.value==='test'?'Testing Google connection...':'Saving securely...';try{const response=await fetch(config.action,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded','X-Requested-With':'dash-ai-config'},body,credentials:'same-origin',cache:'no-store',signal:ctl.signal});if(!response.ok)throw new Error('HTTP '+response.status);window.location.assign(response.url||'/ai?setup=1');}catch(error){if(state)state.textContent=error.name==='AbortError'?'The request timed out. The dashboard is still available.':'The request failed. Check the server log.';buttons.forEach(b=>b.disabled=false);}finally{clearTimeout(timeout);}});"
                + "const form=document.getElementById('ai-composer');if(!form)return;const state=document.getElementById('ai-state'),send=document.getElementById('ai-send'),cancel=document.getElementById('ai-cancel'),messages=document.getElementById('ai-messages');let controller=null;"
                + "function add(role,text){const a=document.createElement('article');a.className='ai-message '+(role==='user'?'is-user':'is-assistant');const d=document.createElement('div'),b=document.createElement('b'),p=document.createElement('p');b.textContent=role==='user'?'You':'Dash AI';p.textContent=text;d.appendChild(b);a.append(d,p);messages.appendChild(a);messages.scrollTop=messages.scrollHeight;return p;}"
                + "form.addEventListener('submit',async e=>{e.preventDefault();const data=new FormData(form),promptText=String(data.get('prompt')||'').trim();if(!promptText)return;add('user',promptText);const answer=add('assistant','Thinking...');state.textContent='Working';send.disabled=true;cancel.classList.remove('hidden');controller=new AbortController();let completed=false,timedOut=false;const watchdog=setTimeout(()=>{timedOut=true;if(controller)controller.abort();fetch('/api/ai/cancel',{method:'POST'}).catch(()=>{});},125000);try{const body=new URLSearchParams();const contexts=[];for(const [k,v] of data.entries()){if(k==='context')contexts.push(v);else body.set(k,v);}body.set('context',contexts.join(','));const res=await fetch('/api/ai/chat',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body,signal:controller.signal});if(!res.ok)throw new Error(await res.text());if(!res.body)throw new Error('The server returned no response stream.');const reader=res.body.getReader(),decoder=new TextDecoder();let buffer='';while(true){const x=await reader.read();if(x.done)break;buffer+=decoder.decode(x.value,{stream:true});const events=buffer.split('\\n\\n');buffer=events.pop()||'';for(const event of events){const line=event.split('\\n').find(v=>v.startsWith('data:'));if(!line)continue;const payload=JSON.parse(line.slice(5));if(payload.type==='result'){completed=true;answer.textContent=payload.response;state.textContent='Ready';const id=form.querySelector('[name=conversation_id]');if(id&&!id.value)id.value=payload.conversation_id;}else if(payload.type==='error'){completed=true;answer.textContent=payload.message||'The AI request failed.';state.textContent='Error';}else state.textContent=payload.message||'Working';}}if(!completed)throw new Error('The AI connection closed before a response arrived.');if(state.textContent==='Ready')form.querySelector('textarea').value='';}catch(err){answer.textContent=timedOut?'The AI request timed out after 125 seconds.':err.name==='AbortError'?'Request cancelled.':String(err.message||err);state.textContent=timedOut?'Timed out':'Error';}finally{clearTimeout(watchdog);send.disabled=false;cancel.classList.add('hidden');controller=null;}});"
                + "cancel.addEventListener('click',()=>{if(controller)controller.abort();fetch('/api/ai/cancel',{method:'POST'}).catch(()=>{});});"
                + "})();</script>";
    }

    private static String human(String value) { return value == null ? "" : value.replace('_', ' '); }
    private static String url(String value) { return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8); }
    private static String esc(String text) { return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
