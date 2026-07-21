package com.naqqa.seofarm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.seofarm.config.SeoFarmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Shared Anthropic (Claude Messages API) client for the SEO Farm — raw HTTP, same pattern as
 * OfferAiService. Used by keyword filtering, competitor analysis, Pexels query generation and blog
 * generation. Returns the model's text; callers strip ```json fences / parse as needed.
 */
@Service
@Slf4j
public class SeoClaudeClient {

    private final String apiKey;
    private final String version;
    private final SeoFarmProperties props;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SeoClaudeClient(
            @Value("${anthropic.api.key:}") String apiKey,
            @Value("${anthropic.api.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${anthropic.api.version:2023-06-01}") String version,
            SeoFarmProperties props) {
        this.apiKey = apiKey;
        this.version = version;
        this.props = props;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** One-shot completion. Returns the concatenated text content, or null on failure. */
    public String complete(int maxTokens, String system, String userText) {
        if (!isConfigured()) {
            return null;
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("max_tokens", maxTokens);
        if (system != null) {
            body.put("system", system);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userText)));
        try {
            JsonNode res = restClient.post().uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", version)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            if (res == null) {
                return null;
            }
            JsonNode content = res.path("content");
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[seofarm] Claude call failed: {}", e.getMessage());
            return null;
        }
    }

    /** Parse a model response that should be JSON, stripping ```json fences first. Null on failure. */
    public JsonNode completeJson(int maxTokens, String system, String userText) {
        String raw = complete(maxTokens, system, userText);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim()
                .replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", "").trim();
        try {
            return mapper.readTree(clean);
        } catch (Exception e) {
            // Try to salvage the outermost JSON object.
            int a = clean.indexOf('{'), b = clean.lastIndexOf('}');
            if (a >= 0 && b > a) {
                try {
                    return mapper.readTree(clean.substring(a, b + 1));
                } catch (Exception ignored) {
                    // fall through
                }
            }
            log.warn("[seofarm] Claude response not valid JSON ({} chars): {}", clean.length(), e.getMessage());
            return null;
        }
    }
}
