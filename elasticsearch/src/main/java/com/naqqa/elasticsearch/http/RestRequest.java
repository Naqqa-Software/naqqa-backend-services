package com.naqqa.elasticsearch.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class RestRequest {

    private final RestMethod method;
    private final String path;
    private final String rawUri;
    private final Map<String, List<String>> queryParams;
    private final HttpHeaders headers;
    private final byte[] content;
    private Map<String, String> pathParams = Map.of();

    public RestRequest(RestMethod method, String path, String rawUri, Map<String, List<String>> queryParams,
                        HttpHeaders headers, byte[] content) {
        this.method = method;
        this.path = path;
        this.rawUri = rawUri;
        this.queryParams = queryParams;
        this.headers = headers;
        this.content = content == null ? new byte[0] : content;
    }

    public RestMethod method() {
        return method;
    }

    public String path() {
        return path;
    }

    public String rawUri() {
        return rawUri;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public String header(String name) {
        return headers.getFirst(name);
    }

    public byte[] content() {
        return content;
    }

    public String contentAsString() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public boolean hasContent() {
        return content.length > 0;
    }

    public void setPathParams(Map<String, String> pathParams) {
        this.pathParams = pathParams;
    }

    public Map<String, String> pathParams() {
        return pathParams;
    }

    public String param(String name) {
        return param(name, null);
    }

    public String param(String name, String defaultValue) {
        String pathValue = pathParams.get(name);
        if (pathValue != null) {
            return pathValue;
        }
        List<String> values = queryParams.get(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(0);
    }

    public boolean hasParam(String name) {
        return pathParams.containsKey(name) || queryParams.containsKey(name);
    }

    public List<String> paramValues(String name) {
        List<String> values = queryParams.get(name);
        if (values == null) {
            String pathValue = pathParams.get(name);
            return pathValue == null ? Collections.emptyList() : List.of(pathValue);
        }
        return values;
    }

    public List<String> paramAsList(String name) {
        List<String> values = paramValues(name);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        if (values.size() > 1) {
            return values;
        }
        return QueryStringParser.splitComma(values.get(0));
    }

    public boolean paramAsBoolean(String name, boolean defaultValue) {
        if (!hasParam(name)) {
            return defaultValue;
        }
        String value = param(name);
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return defaultValue;
    }

    public int paramAsInt(String name, int defaultValue) {
        String value = param(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean pretty() {
        return paramAsBoolean("pretty", false);
    }

    public boolean human() {
        return paramAsBoolean("human", false);
    }

    public boolean errorTrace() {
        return paramAsBoolean("error_trace", false);
    }

    public List<String> filterPathInclude() {
        return filterPath(false);
    }

    public List<String> filterPathExclude() {
        return filterPath(true);
    }

    private List<String> filterPath(boolean excludes) {
        List<String> raw = new ArrayList<>();
        for (String value : paramValues("filter_path")) {
            raw.addAll(QueryStringParser.splitComma(value));
        }
        List<String> result = new ArrayList<>();
        for (String entry : raw) {
            boolean isExclude = entry.startsWith("-");
            String pattern = isExclude ? entry.substring(1) : entry;
            if (isExclude == excludes && !pattern.isEmpty()) {
                result.add(pattern);
            }
        }
        return result;
    }

    public MediaType contentTypeHeader() {
        return MediaType.parse(header("Content-Type"));
    }

    public XContentType contentType() {
        return ContentTypeNegotiator.resolve(contentTypeHeader(), XContentType.JSON);
    }

    public MediaType acceptMediaType() {
        return ContentTypeNegotiator.negotiateAccept(header("Accept"), XContentType.JSON);
    }

    public XContentType accept() {
        return ContentTypeNegotiator.resolve(acceptMediaType(), XContentType.JSON);
    }

    public Integer compatibleWithVersion() {
        MediaType accept = acceptMediaType();
        if (accept != null && accept.compatibleWithVersion() != null) {
            return accept.compatibleWithVersion();
        }
        MediaType contentType = contentTypeHeader();
        return contentType == null ? null : contentType.compatibleWithVersion();
    }
}
