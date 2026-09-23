package dash.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dash.security.HttpSecurity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import dash.security.BasePath;

public final class AiHttpHandler implements HttpHandler {
    private final AiAgentManager manager;
    private final Function<HttpExchange, UserContext> users;
    private final Supplier<String> publicUrl;
    private final Supplier<Boolean> https;
    private final AiAgentManager.ToolExecutor reads;
    private final MutationExecutor mutations;
    private final Consumer<String> audit;
    private final Gson gson = new Gson();

    public AiHttpHandler(AiAgentManager manager, Function<HttpExchange, UserContext> users,
            Supplier<String> publicUrl, Supplier<Boolean> https, AiAgentManager.ToolExecutor reads,
            MutationExecutor mutations, Consumer<String> audit) {
        this.manager = manager;
        this.users = users;
        this.publicUrl = publicUrl;
        this.https = https;
        this.reads = reads;
        this.mutations = mutations;
        this.audit = audit;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        UserContext user = users.apply(exchange);
        if (user == null) { json(exchange, 401, error("unauthorized")); return; }
        String path = exchange.getRequestURI().getPath();
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && path.endsWith("/status")) {
            if (!user.has("dash.web.ai.read")) { json(exchange, 403, error("forbidden")); return; }
            AiAgentManager.ConfigStatus status = manager.status(user.username());
            JsonObject body = new JsonObject();
            body.addProperty("enabled", status.enabled()); body.addProperty("agenticEnabled", status.agenticEnabled());
            body.addProperty("model", status.model()); body.addProperty("configured", status.keyConfigured());
            body.addProperty("ownerAccepted", status.ownerAccepted()); body.addProperty("userAccepted", status.userAccepted());
            body.addProperty("thinkingLevel", status.thinkingLevel()); body.addProperty("maxOutputTokens", status.maxOutputTokens());
            body.addProperty("thinkingSummaries", status.thinkingSummaries()); body.addProperty("toolChoice", status.toolChoice());
            body.addProperty("retryCount", status.retryCount()); body.addProperty("maxProviderCalls", status.maxProviderCalls());
            body.addProperty("requestsPerMinute", status.requestsPerMinute()); body.addProperty("requestsPerDay", status.requestsPerDay());
            body.addProperty("inputTokensPerMinute", status.inputTokensPerMinute());
            json(exchange, 200, body.toString()); return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { json(exchange, 405, error("method_not_allowed")); return; }
        if (!HttpSecurity.isSameOriginMutation(exchange, publicUrl.get())) { audit.accept("AI_CSRF_BLOCKED user=" + user.username()); json(exchange, 403, error("cross_site_blocked")); return; }
        byte[] bytes;
        try { bytes = HttpSecurity.readRequestBody(exchange, 128 * 1024L); }
        catch (Exception ex) { json(exchange, 413, error("request_too_large")); return; }
        Map<String, String> params = form(new String(bytes, StandardCharsets.UTF_8));
        try {
            if (path.endsWith("/consent")) consent(exchange, user, params);
            else if (path.endsWith("/config")) config(exchange, user, params);
            else if (path.endsWith("/chat")) chat(exchange, user, params);
            else if (path.endsWith("/cancel")) cancel(exchange, user);
            else if (path.endsWith("/proposal")) proposal(exchange, user, params);
            else if (path.endsWith("/conversation/delete")) conversationDelete(exchange, user, params);
            else json(exchange, 404, error("not_found"));
        } catch (Exception ex) {
            json(exchange, 400, error(AiRedactor.redact(ex.getMessage(), 240)));
        }
    }

    private void consent(HttpExchange exchange, UserContext user, Map<String, String> params) throws IOException {
        if (!user.has("dash.web.ai.read")) { json(exchange, 403, error("forbidden")); return; }
        boolean owner = "true".equalsIgnoreCase(params.get("owner")) || "owner".equalsIgnoreCase(params.get("scope"));
        if (owner && !user.mainAdmin()) { json(exchange, 403, error("Only the Main Admin can accept owner terms.")); return; }
        if ("revoke".equalsIgnoreCase(params.get("operation"))) manager.revokeConsent(owner ? "__OWNER__" : user.username());
        else {
            if (!"true".equalsIgnoreCase(params.get("agree"))) { json(exchange, 400, error("Current terms must be accepted.")); return; }
            manager.acceptTerms(user.username(), user.role(), owner);
        }
        audit.accept("AI_CONSENT user=" + user.username() + " owner=" + owner + " digest=" + manager.termsDigest());
        redirect(exchange, "/ai?setup=" + (owner ? "1" : "0") + "&msg=" + url("AI terms updated."));
    }

    private void config(HttpExchange exchange, UserContext user, Map<String, String> params) throws IOException {
        if (!user.mainAdmin()) { json(exchange, 403, error("Only the Main Admin can configure Dash AI.")); return; }
        String operation = params.getOrDefault("operation", "save");
        boolean remove = "remove_key".equals(operation);
        boolean enabled = !remove && "true".equalsIgnoreCase(params.get("enabled"));
        boolean agentic = enabled && "true".equalsIgnoreCase(params.get("agentic_enabled"));
        String message = manager.configure(enabled, agentic, params.get("model"), params.get("api_key"), remove,
                params.get("thinking_level"), integer(params, "max_output_tokens", 4096), params.get("seed"),
                params.get("stop_sequences"), params.get("thinking_summaries"), params.get("tool_choice"),
                integer(params, "retry_count", 0), integer(params, "max_provider_calls", 2),
                integer(params, "requests_per_minute", 3), integer(params, "requests_per_day", 15),
                integer(params, "input_tokens_per_minute", 150000));
        if ("test".equals(operation) && !message.contains("before enabling")) message = manager.testConnection();
        audit.accept("AI_CONFIG user=" + user.username() + " enabled=" + enabled + " agentic=" + agentic);
        redirect(exchange, "/ai?setup=1&msg=" + url(message));
    }

    private void chat(HttpExchange exchange, UserContext user, Map<String, String> params) throws IOException {
        if (!user.has("dash.web.ai.use")) { json(exchange, 403, error("forbidden")); return; }
        boolean agentic = "true".equalsIgnoreCase(params.get("agentic")) || "agentic".equalsIgnoreCase(params.get("mode"));
        if (agentic && !user.has("dash.web.ai.agentic")) { json(exchange, 403, error("agentic_permission_required")); return; }
        String prompt = params.getOrDefault("prompt", params.getOrDefault("message", "")).trim();
        if (prompt.isBlank()) { json(exchange, 400, error("message_required")); return; }
        HttpSecurity.applyResponseHeaders(exchange, https.get());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        try {
            event(output, "status", message("Dash AI is working."));
            var future = manager.submitChat(user.username(), params.get("conversation_id"), prompt,
                    selectedContext(params.get("context"), user), agentic, user.permissions(), reads);
            AiAgentManager.ChatResult result;
            int elapsed = 0;
            while (true) {
                try {
                    result = future.get(10, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException waiting) {
                    elapsed += 10;
                    if (elapsed >= 120) throw waiting;
                    event(output, "status", message("Dash AI is still working (" + elapsed + "s)."));
                }
            }
            JsonObject body = new JsonObject(); body.addProperty("conversation_id", result.conversationId());
            body.addProperty("response", result.response()); body.add("proposal_ids", gson.toJsonTree(result.proposalIds()));
            event(output, "result", body.toString());
        } catch (TimeoutException ex) {
            manager.cancel(user.username()); event(output, "error", message("The AI request timed out."));
        } catch (Exception ex) {
            event(output, "error", message(AiRedactor.redact(ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage(), 300)));
        } finally { output.close(); }
    }

    private String selectedContext(String raw, UserContext user) {
        StringBuilder context = new StringBuilder();
        for (String selected : (raw == null ? "" : raw).split(",")) {
            String tool = switch (selected.trim()) {
                case "health" -> "get_server_overview"; case "players" -> "get_players";
                case "logs" -> "get_recent_logs"; case "plugins" -> "get_plugins"; default -> "";
            };
            String permission = manager.requiredPermission(tool);
            if (!tool.isBlank() && user.has(permission)) {
                JsonObject arguments = new JsonObject(); if ("get_recent_logs".equals(tool)) arguments.addProperty("lines", 120);
                context.append('\n').append(tool).append('\n').append(reads.executeRead(tool, arguments, user.username()));
            }
        }
        return AiRedactor.redact(context.toString(), 24_000);
    }

    private void cancel(HttpExchange exchange, UserContext user) throws IOException {
        if (!user.has("dash.web.ai.use")) { json(exchange, 403, error("forbidden")); return; }
        JsonObject body = new JsonObject(); body.addProperty("cancelled", manager.cancel(user.username())); json(exchange, 200, body.toString());
    }

    private void conversationDelete(HttpExchange exchange, UserContext user, Map<String, String> params) throws IOException {
        if (!user.has("dash.web.ai.use")) { json(exchange, 403, error("forbidden")); return; }
        JsonObject body = new JsonObject(); body.addProperty("deleted", manager.deleteConversation(user.username(), params.get("conversation"))); json(exchange, 200, body.toString());
    }

    private void proposal(HttpExchange exchange, UserContext user, Map<String, String> params) throws IOException {
        if (!user.has("dash.web.ai.agentic")) { json(exchange, 403, error("forbidden")); return; }
        String id = params.getOrDefault("proposal_id", params.getOrDefault("proposal", ""));
        String decision = params.getOrDefault("decision", params.getOrDefault("operation", "reject"));
        if ("reject".equals(decision)) {
            boolean done = manager.rejectProposal(id, user.username()); audit.accept("AI_PROPOSAL_REJECTED user=" + user.username() + " id=" + id);
            redirect(exchange, "/ai?msg=" + url(done ? "Proposal rejected." : "Proposal unavailable.")); return;
        }
        String reason = params.getOrDefault("reason", "").trim();
        AiAgentManager.Proposal candidate = manager.proposals(user.username(), false, 100).stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (candidate == null || !"pending".equals(candidate.status())) { json(exchange, 409, error("proposal_unavailable")); return; }
        if (reason.length() < 3 || !user.has(manager.requiredPermission(candidate.tool()))) { json(exchange, 403, error("reason_or_permission_missing")); return; }
        if ("high".equals(candidate.risk()) && !("APPROVE " + candidate.tool()).equals(params.getOrDefault("confirmation", "").trim())) { json(exchange, 400, error("confirmation_mismatch")); return; }
        AiAgentManager.Proposal approved = manager.approveProposal(id, user.username());
        if (approved == null) { json(exchange, 409, error("proposal_expired")); return; }
        Execution result = mutations.execute(approved, user.username(), reason);
        manager.markExecuted(id, result.success(), result.message());
        audit.accept("AI_PROPOSAL_EXECUTED user=" + user.username() + " id=" + id + " tool=" + approved.tool() + " hash=" + approved.argsHash() + " success=" + result.success());
        redirect(exchange, "/ai?msg=" + url(result.message()));
    }

    private void event(OutputStream output, String type, String json) throws IOException {
        JsonObject body; try { body = JsonParser.parseString(json).getAsJsonObject(); } catch (Exception ex) { body = new JsonObject(); body.addProperty("message", json); }
        body.addProperty("type", type); output.write(("event: " + type + "\ndata: " + body + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        HttpSecurity.applyResponseHeaders(exchange, https.get()); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void redirect(HttpExchange exchange, String target) throws IOException {
        HttpSecurity.applyResponseHeaders(exchange, https.get()); exchange.getResponseHeaders().set("Location", BasePath.rewriteLocation(target, BasePath.forRequest(exchange, null))); exchange.sendResponseHeaders(303, -1); exchange.close();
    }

    private static Map<String, String> form(String raw) { Map<String, String> values = new LinkedHashMap<>(); for (String pair : raw.split("&")) { String[] parts = pair.split("=", 2); values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : ""); } return values; }
    private static int integer(Map<String, String> values, String key, int fallback) { try { return Integer.parseInt(values.getOrDefault(key, "")); } catch (Exception ignored) { return fallback; } }
    private static String decode(String value) { try { return URLDecoder.decode(value, StandardCharsets.UTF_8); } catch (Exception ex) { return ""; } }
    private static String url(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private static String error(String value) { JsonObject body = new JsonObject(); body.addProperty("success", false); body.addProperty("error", value); return body.toString(); }
    private static String message(String value) { JsonObject body = new JsonObject(); body.addProperty("message", value); return body.toString(); }

    public record UserContext(String username, String role, boolean mainAdmin, Set<String> permissions) {
        public UserContext { permissions = permissions == null ? Set.of() : Set.copyOf(permissions); }
        public boolean has(String permission) {
            if (permission == null || permission.isBlank()) return true;
            if (permissions.contains("*") || permissions.contains("dash.web.*") || permissions.contains(permission)) return true;
            return permissions.stream().anyMatch(value -> value.endsWith(".*") && permission.startsWith(value.substring(0, value.length() - 1)));
        }
    }
    public interface MutationExecutor { Execution execute(AiAgentManager.Proposal proposal, String username, String reason); }
    public record Execution(boolean success, String message) { }
}
