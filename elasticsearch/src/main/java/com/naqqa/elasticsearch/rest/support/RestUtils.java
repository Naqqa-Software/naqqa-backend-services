package com.naqqa.elasticsearch.rest.support;

import com.naqqa.elasticsearch.common.json.JsonParseException;
import com.naqqa.elasticsearch.common.json.JsonParser;
import com.naqqa.elasticsearch.http.FilterPath;
import com.naqqa.elasticsearch.http.Json;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class RestUtils {

    private RestUtils() {
    }

    public static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(cause);
        }
    }

    public static Map<String, Object> parseBody(RestRequest request) {
        if (!request.hasContent()) {
            return new LinkedHashMap<>();
        }
        try (JsonParser parser = new JsonParser(request.content())) {
            return parser.map();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Failed to parse request body: " + e.getMessage(), e);
        }
    }

    public static boolean requireIndex(String index) {
        return index != null && !index.isBlank();
    }

    public static RestResponse jsonResponse(RestRequest request, int status, Map<String, Object> body) {
        Object filtered = FilterPath.apply(body, request.filterPathInclude(), request.filterPathExclude());
        return RestResponse.json(status, Json.write(filtered, request.pretty()));
    }

    public static void sendJson(RestChannel channel, RestRequest request, int status, Map<String, Object> body) {
        channel.sendResponse(jsonResponse(request, status, body));
    }

    public static RestResponse ndjsonResponse(String body) {
        return new RestResponse(200, "application/x-ndjson; charset=UTF-8", body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void requireContent(RestRequest request) {
        if (!request.hasContent()) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    public static Long paramAsLong(RestRequest request, String name) {
        String v = request.param(name);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("failed to parse [" + name + "] as a long: " + v, e);
        }
    }

    public static List<String> commaListOrEmpty(RestRequest request, String name) {
        return request.paramAsList(name);
    }

    public static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
