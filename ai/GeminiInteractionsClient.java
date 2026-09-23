package dash.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class GeminiInteractionsClient {
    public static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    public static final Set<String> MODELS = Set.of("gemini-3.7-flash", "gemini-2.5-pro");
    private final HttpClient client;
    private final URI endpoint;
    private final Gson gson = new Gson();

    public GeminiInteractionsClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build(), URI.create(DEFAULT_ENDPOINT));
    }

    GeminiInteractionsClient(HttpClient client, URI endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    public void verifyKey(String apiKey, String model) throws IOException, InterruptedException {
        String selectedModel = MODELS.contains(model) ? model : "gemini-3.7-flash";
        URI modelEndpoint = endpoint.resolve("models/" + selectedModel);
        HttpRequest request = HttpRequest.newBuilder(modelEndpoint)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("x-goog-api-key", apiKey)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(providerError(response.statusCode(), response.body()));
        }
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!body.has("name") || !body.get("name").getAsString().endsWith(selectedModel)) {
                throw new IOException("Google returned unexpected model metadata.");
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Google returned invalid model metadata.", ex);
        }
    }

    public InteractionResult create(String apiKey, String model, JsonElement input, JsonArray tools,
            String previousInteractionId) throws IOException, InterruptedException {
        return create(apiKey, model, input, tools, previousInteractionId, "");
    }

    public InteractionResult create(String apiKey, String model, JsonElement input, JsonArray tools,
            String previousInteractionId, String systemInstruction) throws IOException, InterruptedException {
        return create(apiKey, model, input, tools, previousInteractionId, systemInstruction, null);
    }

    public InteractionResult create(String apiKey, String model, JsonElement input, JsonArray tools,
            String previousInteractionId, String systemInstruction, JsonObject generationConfig)
            throws IOException, InterruptedException {
        return create(apiKey, model, input, tools, previousInteractionId, systemInstruction, generationConfig, 2);
    }

    public InteractionResult create(String apiKey, String model, JsonElement input, JsonArray tools,
            String previousInteractionId, String systemInstruction, JsonObject generationConfig, int maxRetries)
            throws IOException, InterruptedException {
        String selectedModel = MODELS.contains(model) ? model : "gemini-3.7-flash";
        JsonObject payload = new JsonObject();
        payload.addProperty("model", selectedModel);
        payload.addProperty("store", false);
        payload.add("input", input);
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            payload.addProperty("system_instruction", systemInstruction);
        }
        if (generationConfig != null && !generationConfig.isEmpty()) {
            payload.add("generation_config", generationConfig.deepCopy());
        }
        if (tools != null && !tools.isEmpty()) payload.add("tools", tools);
        if (previousInteractionId != null && !previousInteractionId.isBlank()) {
            payload.addProperty("previous_interaction_id", previousInteractionId);
        }
        String requestBody = gson.toJson(payload);
        IOException last = null;
        List<Integer> transientStatuses = new ArrayList<>();
        int attempts = Math.max(1, Math.min(3, maxRetries + 1));
        for (int attempt = 0; attempt < attempts; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException ex) {
                last = ex;
                if (attempt == attempts - 1) throw ex;
                sleepBackoff(attempt);
                continue;
            }
            int status = response.statusCode();
            if ((status == 429 || status >= 500) && attempt < attempts - 1) {
                transientStatuses.add(status);
                sleepBackoff(attempt);
                continue;
            }
            if (status < 200 || status >= 300) {
                transientStatuses.add(status);
                String history = transientStatuses.size() > 1
                        ? " Provider attempts returned HTTP " + transientStatuses + "."
                        : "";
                throw new IOException(providerError(status, response.body()) + history);
            }
            return parse(response.body());
        }
        throw last == null ? new IOException("Gemini request failed.") : last;
    }

    private InteractionResult parse(String body) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String id = string(root, "id");
            StringBuilder output = new StringBuilder(string(root, "output_text"));
            StringBuilder thoughtSummary = new StringBuilder();
            List<FunctionCall> calls = new ArrayList<>();
            JsonArray steps = root.has("steps") && root.get("steps").isJsonArray()
                    ? root.getAsJsonArray("steps") : new JsonArray();
            for (JsonElement element : steps) {
                if (!element.isJsonObject()) continue;
                JsonObject step = element.getAsJsonObject();
                String type = string(step, "type");
                if ("model_output".equals(type)) {
                    JsonArray content = step.has("content") && step.get("content").isJsonArray()
                            ? step.getAsJsonArray("content") : new JsonArray();
                    for (JsonElement itemElement : content) {
                        if (!itemElement.isJsonObject()) continue;
                        JsonObject item = itemElement.getAsJsonObject();
                        if (!"text".equals(string(item, "type"))) continue;
                        String text = string(item, "text");
                        if (text.isBlank()) continue;
                        if (!output.isEmpty()) output.append('\n');
                        output.append(text);
                    }
                } else if ("thought".equals(type)) {
                    appendTextContent(step, "summary", thoughtSummary);
                } else if ("function_call".equals(type)) {
                    String callId = string(step, "id");
                    String name = string(step, "name");
                    JsonObject arguments = step.has("arguments") && step.get("arguments").isJsonObject()
                            ? step.getAsJsonObject("arguments") : new JsonObject();
                    if (!callId.isBlank() && !name.isBlank()) calls.add(new FunctionCall(callId, name, arguments));
                }
            }
            String outputText = output.toString();
            if (outputText.isBlank() && calls.isEmpty()) throw new IOException("Gemini returned no usable output.");
            return new InteractionResult(id, outputText, thoughtSummary.toString(), List.copyOf(calls));
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Gemini returned an invalid response.", ex);
        }
    }

    static String providerError(int status, String body) {
        String message = "";
        String providerStatus = "";
        String providerDetails = "";
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonArray() && !parsed.getAsJsonArray().isEmpty()) parsed = parsed.getAsJsonArray().get(0);
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                message = string(error, "message");
                providerStatus = string(error, "status");
                providerDetails = quotaDetails(error);
            } else {
                message = string(root, "message");
                providerStatus = string(root, "status");
            }
        } catch (Exception ignored) { }
        message = AiRedactor.redact(message, 300);
        String label = providerStatus.isBlank() ? "" : " (" + AiRedactor.redact(providerStatus, 80) + ")";
        String detail = providerDetails.isBlank() ? "" : " " + AiRedactor.redact(providerDetails, 220);
        String reason = message.isBlank() ? "No provider explanation was returned." : message;
        return "Google Gemini returned HTTP " + status + label + ": " + reason + detail;
    }

    private static String quotaDetails(JsonObject error) {
        if (!error.has("details") || !error.get("details").isJsonArray()) return "";
        String quota = "";
        String retry = "";
        for (JsonElement element : error.getAsJsonArray("details")) {
            if (!element.isJsonObject()) continue;
            JsonObject detail = element.getAsJsonObject();
            if (retry.isBlank()) retry = string(detail, "retryDelay");
            if (!detail.has("violations") || !detail.get("violations").isJsonArray()
                    || detail.getAsJsonArray("violations").isEmpty()) continue;
            JsonElement first = detail.getAsJsonArray("violations").get(0);
            if (!first.isJsonObject()) continue;
            JsonObject violation = first.getAsJsonObject();
            quota = string(violation, "quotaMetric");
            if (quota.isBlank()) quota = string(violation, "quotaId");
        }
        StringBuilder value = new StringBuilder();
        if (!quota.isBlank()) value.append("Quota: ").append(quota).append('.');
        if (!retry.isBlank()) value.append(value.isEmpty() ? "" : " ").append("Retry after ").append(retry).append('.');
        return value.toString();
    }

    private static String string(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private static void appendTextContent(JsonObject source, String key, StringBuilder target) {
        JsonArray content = source.has(key) && source.get(key).isJsonArray() ? source.getAsJsonArray(key) : new JsonArray();
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!"text".equals(string(item, "type"))) continue;
            String value = string(item, "text");
            if (value.isBlank()) continue;
            if (!target.isEmpty()) target.append('\n');
            target.append(value);
        }
    }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        Thread.sleep(attempt == 0 ? 1_000L : 2_000L);
    }

    public record FunctionCall(String id, String name, JsonObject arguments) { }
    public record InteractionResult(String id, String outputText, String thoughtSummary, List<FunctionCall> calls) { }
}
