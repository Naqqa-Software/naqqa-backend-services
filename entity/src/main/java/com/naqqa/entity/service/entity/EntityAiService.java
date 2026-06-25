package com.naqqa.entity.service.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a complete entity-definition blueprint from a natural-language prompt
 * using Claude (Anthropic Messages API, raw HTTP via {@link RestClient}).
 *
 * <p>The model is taught the exact JSON shape the designer form consumes
 * ({@code { mainDetails, api, fields, indexes, uniqueConstraints, ui }}) and
 * returns only that object, which the frontend patches straight into the form.
 * Mirrors the builder/offer/task AI services used elsewhere in the platform.
 */
@Service
@Slf4j
public class EntityAiService {

    /** Bound the output so a huge entity can't blow past the model's limit. */
    private static final int MAX_TOKENS = 16000;

    private final String apiKey;
    private final String model;
    private final String version;
    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EntityAiService(
            @Value("${anthropic.api.key:}") String apiKey,
            @Value("${anthropic.api.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${anthropic.api.model:claude-opus-4-8}") String model,
            @Value("${anthropic.api.version:2023-06-01}") String version) {
        this.apiKey = apiKey;
        this.model = model;
        this.version = version;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Produces a full entity-definition object from a prompt. When {@code current}
     * is a populated definition, the prompt edits it (e.g. "add a priority field");
     * otherwise a fresh entity is created (e.g. "create entity for FAQ"). Returns
     * the blueprint as a JSON object map in the exact shape the designer form expects.
     */
    public Map<String, Object> generate(String prompt, JsonNode current) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI is not configured. Set ANTHROPIC_API_KEY on the server.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt is required.");
        }

        String userText;
        if (isPopulated(current)) {
            userText = "You are EDITING an existing entity definition. Apply the requested change to it "
                    + "and return the COMPLETE updated definition as one JSON object — preserve every field "
                    + "and value not mentioned, and keep mainDetails.key unless the request explicitly renames "
                    + "it.\n\nRequested change:\n" + prompt.trim()
                    + "\n\nCurrent definition (JSON):\n" + current.toString();
        } else {
            userText = "Design a new entity definition for the following request and output ONLY the JSON "
                    + "object described in the system instructions:\n\n" + prompt.trim();
        }

        String response = callModel(SYSTEM_PROMPT, userText);
        JsonNode root = parseJson(response);
        if (!root.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI returned an unexpected shape.");
        }
        normalize((ObjectNode) root);
        return mapper.convertValue(root, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    // ── Anthropic call + JSON parsing (mirrors BuilderAiService) ──────────────

    private String callModel(String system, String userText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user",
                "content", List.of(Map.of("type", "text", "text", userText)))));
        body.put("thinking", Map.of("type", "adaptive"));
        body.put("output_config", Map.of("effort", "high"));

        try {
            return restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", version)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            log.error("Anthropic entity generation request failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI generation failed: " + e.getMessage());
        }
    }

    /** Reads the first text block out of the Anthropic response and parses it as JSON. */
    private JsonNode parseJson(String response) {
        try {
            JsonNode root = mapper.readTree(response);
            String text = null;
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }
            if (text == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI returned no content.");
            }
            return mapper.readTree(stripFences(text));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse entity generation response", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not parse the AI response.");
        }
    }

    /** Trims markdown code fences and any prose around the JSON object. */
    private String stripFences(String text) {
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    /** True when `current` looks like a real definition to edit (has a key, a label, or fields). */
    private boolean isPopulated(JsonNode current) {
        if (current == null || current.isNull() || !current.isObject()) {
            return false;
        }
        JsonNode main = current.path("mainDetails");
        boolean hasKey = !main.path("key").asText("").isBlank();
        boolean hasLabel = !main.path("label").asText("").isBlank();
        boolean hasFields = current.path("fields").isArray() && current.path("fields").size() > 0;
        return hasKey || hasLabel || hasFields;
    }

    /** Fills sane defaults the form relies on, so a terse model answer still loads cleanly. */
    private void normalize(ObjectNode root) {
        ObjectNode main = root.has("mainDetails") && root.get("mainDetails").isObject()
                ? (ObjectNode) root.get("mainDetails")
                : root.putObject("mainDetails");
        if (!main.hasNonNull("version") || !main.get("version").isNumber()) {
            main.put("version", 1);
        }
        if (!main.has("isActive")) {
            main.put("isActive", true);
        }
    }

    private static final String SYSTEM_PROMPT = """
            You are a schema designer for a dynamic-entity platform. Given a short natural-language
            request, you produce ONE entity-definition blueprint as a single JSON object. The object is
            patched directly into a form, so the shape must be EXACT and you must output ONLY the JSON
            object — no markdown, no code fences, no commentary.

            TOP-LEVEL SHAPE:
            {
              "mainDetails": { "key": string, "slug": string, "label": string, "version": 1,
                               "description": string, "isActive": true },
              "api": { "isPublicGet": bool, "isPublicPost": bool, "isPublicPut": bool,
                       "allowFilters": bool, "allowSort": bool, "allowSearch": bool },
              "fields": EntityField[],
              "indexes": EntityIndex[],
              "uniqueConstraints": UniqueConstraint[],
              "ui": { "defaultSort": { "path": string, "direction": "asc"|"desc" },
                      "tableGroups": [ { "key": string, "label": string, "order": number } ] }
            }

            EntityField:
            {
              "key": string (camelCase, unique within the entity),
              "type": one of "text","textarea","number","boolean","date","datetime","email","phone",
                      "url","slug","password","html","json","image","file","object","array",
              "widget": one of "input","textarea","select","multiselect","radio","checkbox","toggle",
                        "date","datetime","richtext","jsoneditor","image","file","template",
              "defaultValue": any (optional),
              "props": {
                "label": string, "placeholder": string, "required": bool,
                "min": number, "max": number, "minLength": number, "maxLength": number,
                "showTableFilter": bool, "showTableSort": bool,
                "options": [ { "label": string, "value": string } ]   // ONLY for select/multiselect/radio
              },
              "validation": { "messages": [ { "errorKey": "required", "message": string } ] }
            }

            EntityIndex:  { "name": string, "fields": [ { "path": string, "direction": 1|-1 } ], "unique": bool, "sparse": bool }
            UniqueConstraint: { "fields": [ { "path": string } ], "scopeFields": [ { "path": string } ], "caseInsensitive": bool }

            RULES:
            - If a "Current definition" is supplied, you are EDITING: start from it, apply ONLY the
              requested change, keep every other field and value exactly as-is, and return the full
              updated object (do not drop existing fields, and keep mainDetails.key unless asked to rename).
            - Otherwise you are creating a NEW entity: derive `key` (singular camelCase, e.g. "faq"),
              `slug` (plural kebab/lowercase, e.g. "faqs") and a human `label` from the request, and set
              version=1, isActive=true.
            - Pick a `widget` that matches each `type` (text->input, boolean->checkbox/toggle,
              an enumerated choice->select with `options`, long text->textarea or richtext, etc.).
            - Add the fields a real-world version of this entity needs (typically 3–8). Give each a clear
              label and placeholder; mark the important ones required with a matching validation message.
            - Set api.allowFilters/allowSort/allowSearch = true; keep isPublic* = false unless the request
              clearly asks for a public API.
            - Add a sensible ui.defaultSort. Add a unique index for any natural key field. Use
              indexes/uniqueConstraints only when warranted; otherwise use empty arrays.
            - Do NOT include expressions, hooks, validators.fn, asyncValidators, modelOptions, or event
              handlers — keep the blueprint to the fields above.
            - Output ONLY the JSON object.
            """;
}
