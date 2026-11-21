package ee.ut.proofofprompt.proof_of_prompt.workflow;

import ee.ut.proofofprompt.proof_of_prompt.crypto.CommittedRequest;
import ee.ut.proofofprompt.proof_of_prompt.crypto.PromptMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public final class GroqChatClient {
    private final HttpClient httpClient;
    private final URI chatEndpoint;
    private final String apiKey;
    private final JsonMapper jsonMapper;

    public GroqChatClient(HttpClient httpClient, URI chatEndpoint, String apiKey, JsonMapper jsonMapper) {
        if (apiKey == null || apiKey.isBlank() || !apiKey.matches("[!-~]+")) {
            throw new IllegalArgumentException("Configure a valid GROQ_API_KEY before accepting requests");
        }
        this.httpClient = httpClient;
        this.chatEndpoint = chatEndpoint;
        this.apiKey = apiKey;
        this.jsonMapper = jsonMapper;
    }

    public ModelExecutionOutcome callModel(String selectedModelId, CommittedRequest committedRequest)
            throws InterruptedException {
        ObjectNode payload = jsonMapper.createObjectNode();
        payload.put("model", selectedModelId);
        payload.put("reasoning_effort", "low");
        payload.put("max_completion_tokens", 1024);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        for (PromptMessage message : committedRequest.messages()) {
            messages.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT))
                    .put("content", message.content());
        }
        HttpRequest request = HttpRequest.newBuilder(chatEndpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonMapper.writeValueAsBytes(payload)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return ModelExecutionOutcome.failed("Groq returned HTTP " + response.statusCode());
            }
            JsonNode responseBody = jsonMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body());
            if (responseBody == null || !responseBody.isObject()) {
                return ModelExecutionOutcome.failed("Groq returned an invalid response object");
            }
            JsonNode message = responseBody.path("choices").path(0).path("message");
            if (!"assistant".equals(message.path("role").asString(""))) {
                return ModelExecutionOutcome.failed("Groq returned no assistant message");
            }
            JsonNode responseText = message.path("content");
            if (!responseText.isString() || responseText.asString().isBlank()) {
                return ModelExecutionOutcome.failed("Groq returned no assistant text");
            }
            return ModelExecutionOutcome.succeeded(responseText.asString());
        } catch (HttpTimeoutException timeout) {
            return ModelExecutionOutcome.failed("Groq did not respond within the timeout");
        } catch (IOException connectionFailure) {
            return ModelExecutionOutcome.failed("Could not complete the connection to Groq");
        } catch (JacksonException invalidResponse) {
            return ModelExecutionOutcome.failed("Groq returned invalid JSON");
        }
    }
}
