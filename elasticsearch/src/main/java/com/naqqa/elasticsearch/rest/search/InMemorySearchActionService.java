package com.naqqa.elasticsearch.rest.search;

import com.naqqa.elasticsearch.common.UUIDs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemorySearchActionService implements SearchActionService {

    private final Map<String, String> scrollContexts = new ConcurrentHashMap<>();
    private final Map<String, String> pointInTimes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> asyncSearches = new ConcurrentHashMap<>();

    private Map<String, Object> emptySearchResponse() {
        Map<String, Object> hits = new LinkedHashMap<>();
        hits.put("total", Map.of("value", 0, "relation", "eq"));
        hits.put("max_score", null);
        hits.put("hits", List.of());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("took", 1);
        response.put("timed_out", false);
        response.put("_shards", Map.of("total", 1, "successful", 1, "skipped", 0, "failed", 0));
        response.put("hits", hits);
        return response;
    }

    @Override
    public CompletableFuture<Map<String, Object>> search(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        return CompletableFuture.completedFuture(emptySearchResponse());
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> multiSearch(List<MsearchItem> items) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (MsearchItem item : items) {
            results.add(emptySearchResponse());
        }
        return CompletableFuture.completedFuture(results);
    }

    @Override
    public CompletableFuture<Map<String, Object>> count(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 0);
        result.put("_shards", Map.of("total", 1, "successful", 1, "skipped", 0, "failed", 0));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> explain(String index, String id, Map<String, Object> requestBody, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_index", index);
        result.put("_id", id);
        result.put("matched", false);
        result.put("explanation", Map.of("value", 0.0, "description", "no matching query", "details", List.of()));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> validateQuery(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("_shards", Map.of("total", 1, "successful", 1, "failed", 0));
        if (Boolean.parseBoolean(params.getOrDefault("explain", "false"))) {
            result.put("explanations", List.of(Map.of("index", indices.isEmpty() ? "_all" : indices.get(0), "valid", true, "explanation", "*:*")));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> fieldCaps(List<String> indices, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indices", indices);
        result.put("fields", Map.of());
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> scroll(String scrollId, String scrollTtl) {
        if (scrollId == null || !scrollContexts.containsKey(scrollId)) {
            String newId = scrollId != null ? scrollId : UUIDs.randomBase64UUID();
            scrollContexts.put(newId, scrollTtl);
            Map<String, Object> response = emptySearchResponse();
            response.put("_scroll_id", newId);
            return CompletableFuture.completedFuture(response);
        }
        Map<String, Object> response = emptySearchResponse();
        response.put("_scroll_id", scrollId);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<Map<String, Object>> clearScroll(List<String> scrollIds) {
        int removed = 0;
        for (String id : scrollIds) {
            if (scrollContexts.remove(id) != null) {
                removed++;
            }
        }
        return CompletableFuture.completedFuture(Map.of("succeeded", true, "num_freed", removed));
    }

    @Override
    public CompletableFuture<Map<String, Object>> openPointInTime(List<String> indices, Map<String, String> params) {
        String id = UUIDs.randomBase64UUID();
        pointInTimes.put(id, String.join(",", indices));
        return CompletableFuture.completedFuture(Map.of("id", id));
    }

    @Override
    public CompletableFuture<Map<String, Object>> closePointInTime(String pitId) {
        boolean removed = pointInTimes.remove(pitId) != null;
        return CompletableFuture.completedFuture(Map.of("succeeded", removed, "num_freed", removed ? 1 : 0));
    }

    @Override
    public CompletableFuture<Map<String, Object>> submitAsyncSearch(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        String id = UUIDs.randomBase64UUID();
        Map<String, Object> response = emptySearchResponse();
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("id", id);
        stored.put("is_partial", false);
        stored.put("is_running", false);
        stored.put("start_time_in_millis", System.currentTimeMillis());
        stored.put("expiration_time_in_millis", System.currentTimeMillis() + 300_000L);
        stored.put("response", response);
        asyncSearches.put(id, stored);
        Map<String, Object> result = new LinkedHashMap<>(stored);
        result.remove("response");
        result.putAll(response);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAsyncSearch(String id, Map<String, String> params) {
        Map<String, Object> stored = asyncSearches.get(id);
        if (stored == null) {
            return CompletableFuture.failedFuture(new com.naqqa.elasticsearch.rest.support.RestApiException(404, "no async search found for id [" + id + "]"));
        }
        Map<String, Object> result = new LinkedHashMap<>(stored);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) result.remove("response");
        result.putAll(response);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> deleteAsyncSearch(String id) {
        boolean removed = asyncSearches.remove(id) != null;
        if (!removed) {
            return CompletableFuture.failedFuture(new com.naqqa.elasticsearch.rest.support.RestApiException(404, "no async search found for id [" + id + "]"));
        }
        return CompletableFuture.completedFuture(Map.of("acknowledged", true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> searchTemplate(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        return CompletableFuture.completedFuture(emptySearchResponse());
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> renderTemplate(Map<String, Object> requestBody) {
        Object source = requestBody.get("source");
        Map<String, Object> params = requestBody.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String rendered = source == null ? "" : renderMustacheLite(String.valueOf(source), params);
        return CompletableFuture.completedFuture(Map.of("template_output", Map.of("rendered", rendered)));
    }

    private static String renderMustacheLite(String template, Map<String, Object> params) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("{{", i);
            if (start < 0) {
                result.append(template, i, template.length());
                break;
            }
            result.append(template, i, start);
            int end = template.indexOf("}}", start + 2);
            if (end < 0) {
                result.append(template.substring(start));
                break;
            }
            String key = template.substring(start + 2, end).trim();
            Object value = params.get(key);
            result.append(value == null ? "" : String.valueOf(value));
            i = end + 2;
        }
        return result.toString();
    }

    @Override
    public CompletableFuture<Map<String, Object>> rankEval(List<String> indices, Map<String, Object> requestBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metric_score", 0.0);
        result.put("details", Map.of());
        result.put("failures", Map.of());
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> termsEnum(String index, Map<String, Object> requestBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_shards", Map.of("total", 1, "successful", 1, "failed", 0));
        result.put("terms", List.of());
        result.put("complete", true);
        return CompletableFuture.completedFuture(result);
    }
}
