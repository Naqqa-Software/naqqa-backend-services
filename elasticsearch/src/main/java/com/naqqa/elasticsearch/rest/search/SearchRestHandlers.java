package com.naqqa.elasticsearch.rest.search;

import com.naqqa.elasticsearch.common.json.NdJson;
import com.naqqa.elasticsearch.http.QueryStringParser;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.rest.support.RestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SearchRestHandlers {

    private static final List<String> SEARCH_PARAM_NAMES = List.of("from", "size", "q", "df", "default_operator",
        "analyzer", "explain", "version", "track_total_hits", "terminate_after", "timeout", "scroll", "search_type",
        "typed_keys", "allow_partial_search_results", "preference", "routing", "request_cache",
        "batched_reduce_size", "rest_total_hits_as_int", "max_concurrent_shard_requests",
        "wait_for_completion_timeout", "keep_alive", "keep_on_completion", "min_score", "ignore_unavailable",
        "ignore_throttled", "allow_no_indices", "expand_wildcards");

    private final SearchActionService service;

    public SearchRestHandlers(SearchActionService service) {
        this.service = service;
    }

    private static List<String> resolveIndices(RestRequest request) {
        return request.paramAsList("index");
    }

    private static Map<String, String> simpleParams(RestRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String name : SEARCH_PARAM_NAMES) {
            String v = request.param(name);
            if (v != null) {
                params.put(name, v);
            }
        }
        return params;
    }

    public void search(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.search(indices, body, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    @SuppressWarnings("unchecked")
    public void multiSearch(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        String defaultIndex = request.param("index");
        List<Object> lines = NdJson.readAll(request.content());
        List<SearchActionService.MsearchItem> items = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            Map<String, Object> header = lines.get(i) instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            i++;
            if (i >= lines.size()) {
                throw new IllegalArgumentException("msearch header line without a following body line");
            }
            Map<String, Object> body = lines.get(i) instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            i++;
            List<String> indices = new ArrayList<>();
            if (header.get("index") instanceof List<?> list) {
                for (Object o : list) {
                    indices.add(String.valueOf(o));
                }
            } else if (header.get("index") != null) {
                indices.addAll(QueryStringParser.splitComma(String.valueOf(header.get("index"))));
            } else if (defaultIndex != null) {
                indices.addAll(QueryStringParser.splitComma(defaultIndex));
            }
            items.add(new SearchActionService.MsearchItem(indices, header, body));
        }
        List<Map<String, Object>> responses = RestUtils.await(service.multiSearch(items));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("took", 0);
        result.put("responses", responses);
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void count(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.count(indices, body, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void explain(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        String id = request.param("id");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.explain(index, id, body, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void validateQuery(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.validateQuery(indices, body, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void fieldCaps(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        Map<String, Object> result = RestUtils.await(service.fieldCaps(indices, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    @SuppressWarnings("unchecked")
    public void scroll(RestRequest request, RestChannel channel) {
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : Map.of();
        String scrollId = body.get("scroll_id") != null ? String.valueOf(body.get("scroll_id")) : request.param("scroll_id");
        String scrollTtl = body.get("scroll") != null ? String.valueOf(body.get("scroll")) : request.param("scroll", "1m");
        if (scrollId == null) {
            throw new IllegalArgumentException("scroll_id is required");
        }
        Map<String, Object> result = RestUtils.await(service.scroll(scrollId, scrollTtl));
        RestUtils.sendJson(channel, request, 200, result);
    }

    @SuppressWarnings("unchecked")
    public void clearScroll(RestRequest request, RestChannel channel) {
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : Map.of();
        List<String> ids = new ArrayList<>();
        if (body.get("scroll_id") instanceof List<?> list) {
            for (Object o : list) {
                ids.add(String.valueOf(o));
            }
        } else if (body.get("scroll_id") != null) {
            ids.add(String.valueOf(body.get("scroll_id")));
        }
        if (ids.isEmpty() && request.param("scroll_id") != null) {
            ids.addAll(request.paramAsList("scroll_id"));
        }
        Map<String, Object> result = RestUtils.await(service.clearScroll(ids));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void openPointInTime(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        Map<String, Object> result = RestUtils.await(service.openPointInTime(indices, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void closePointInTime(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        String id = body.get("id") != null ? String.valueOf(body.get("id")) : null;
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        Map<String, Object> result = RestUtils.await(service.closePointInTime(id));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void submitAsyncSearch(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.submitAsyncSearch(indices, body, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void getAsyncSearch(RestRequest request, RestChannel channel) {
        String id = request.param("id");
        Map<String, Object> result = RestUtils.await(service.getAsyncSearch(id, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void deleteAsyncSearch(RestRequest request, RestChannel channel) {
        String id = request.param("id");
        Map<String, Object> result = RestUtils.await(service.deleteAsyncSearch(id));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void searchTemplate(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.searchTemplate(indices, body, simpleParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void renderTemplate(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.renderTemplate(body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void rankEval(RestRequest request, RestChannel channel) {
        List<String> indices = resolveIndices(request);
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.rankEval(indices, body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void termsEnum(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.termsEnum(index, body));
        RestUtils.sendJson(channel, request, 200, result);
    }
}
