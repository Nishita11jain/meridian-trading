package com.trading.api.extensions.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * The one place the Gemini API is called. The key is read from the environment on the server and sent in
 * a header, never in a URL, and it never reaches the browser. With no key configured the assistant is
 * unavailable rather than failing on every call.
 */
@Component
public class GeminiClient {

    static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    static final int MAX_OUTPUT_TOKENS = 400;

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public GeminiClient(@Value("${assistant.gemini-api-key:}") String apiKey,
                        @Value("${assistant.gemini-model:gemini-3.5-flash-lite}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5_000);
        requests.setReadTimeout(20_000);
        this.restClient = RestClient.builder().baseUrl(BASE_URL).requestFactory(requests).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Asks the model for one answer. Throws ASST-503 when the key is missing or the call fails. */
    public String generate(String systemInstruction, String userText) {
        if (!isConfigured()) {
            throw new AssistantUnavailableException("The assistant is not configured on this server.");
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))),
                "generationConfig", Map.of("maxOutputTokens", MAX_OUTPUT_TOKENS, "temperature", 0.3));
        try {
            JsonNode response = restClient.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String text = response == null ? null
                    : response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText(null);
            if (text == null || text.isBlank()) {
                throw new AssistantUnavailableException("The assistant returned no answer. Try rephrasing.");
            }
            return text.trim();
        } catch (RestClientException e) {
            throw new AssistantUnavailableException("The assistant is unavailable right now.");
        }
    }
}
