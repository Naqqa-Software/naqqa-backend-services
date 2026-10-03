package com.naqqa.chatbot.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class OllamaLlmProvider implements LlmProvider {

    public static final String PROVIDER_OLLAMA = "ollama";
    private static final long HEALTH_TTL_MS = 60_000L;

    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "text", Map.of("type", "string"),
                    "ids", Map.of("type", "array", "items", Map.of("type", "string")),
                    "confidence", Map.of("type", "number"),
                    "escalate", Map.of("type", "boolean")),
            "required", List.of("text", "ids", "confidence", "escalate"));

    private final boolean enabled;
    private final String model;
    private final RestClient client;
    private final RestClient healthClient;
    private LlmResponseParser parser = new LlmResponseParser(null);
    private volatile Boolean healthy;
    private volatile long checkedAt;

    public OllamaLlmProvider(String provider, String url, String model, long timeoutMs) {
        this.enabled = PROVIDER_OLLAMA.equals(provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT));
        this.model = model == null || model.isBlank() ? "qwen2.5:3b-instruct" : model.trim();
        String base = url == null || url.isBlank() ? "http://localhost:11434" : url.trim().replaceAll("/+$", "");
        this.client = build(base, Duration.ofMillis(timeoutMs <= 0 ? 15_000L : timeoutMs));
        this.healthClient = build(base, Duration.ofMillis(2_000L));
    }

    private static RestClient build(String base, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1_500)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    public void setParser(LlmResponseParser parser) {
        this.parser = parser == null ? new LlmResponseParser(null) : parser;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public String model() {
        return model;
    }

    @Override
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        Boolean h = healthy;
        if (h != null && now - checkedAt < HEALTH_TTL_MS) {
            return h;
        }
        boolean ok;
        try {
            ok = ping();
        } catch (RuntimeException e) {
            ok = false;
        }
        healthy = ok;
        checkedAt = now;
        if (!ok) {
            log.debug("[chatbot] ollama not reachable; search-only mode");
        }
        return ok;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        if (!enabled) {
            return null;
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.system() != null) {
            messages.add(Map.of("role", "system", "content", request.system()));
        }
        for (LlmMessage m : request.messages()) {
            messages.add(Map.of("role", m.role(), "content", m.content()));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.2);
        options.put("num_ctx", 4096);
        options.put("num_predict", request.maxTokens() > 0 ? request.maxTokens() : 300);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("format", RESPONSE_SCHEMA);
        body.put("options", options);
        body.put("keep_alive", "30m");
        try {
            JsonNode res = post("/api/chat", body);
            if (res == null) {
                return null;
            }
            String content = res.path("message").path("content").asText("");
            int in = res.path("prompt_eval_count").asInt(0);
            int out = res.path("eval_count").asInt(0);
            return parser.parse(content, in, out);
        } catch (RuntimeException e) {
            log.warn("[chatbot] ollama generation failed: {}", e.getMessage());
            healthy = false;
            checkedAt = System.currentTimeMillis();
            return null;
        }
    }

    protected boolean ping() {
        JsonNode tags = healthClient.get().uri("/api/tags").retrieve().body(JsonNode.class);
        return tags != null && tags.has("models");
    }

    protected JsonNode post(String path, Map<String, Object> body) {
        return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
    }
}
