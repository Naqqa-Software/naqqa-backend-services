package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FetchSourceContext {

    public static final FetchSourceContext FETCH_SOURCE = new FetchSourceContext(true, List.of(), List.of());
    public static final FetchSourceContext DO_NOT_FETCH_SOURCE = new FetchSourceContext(false, List.of(), List.of());

    private final boolean fetchSource;
    private final List<String> includes;
    private final List<String> excludes;

    public FetchSourceContext(boolean fetchSource, List<String> includes, List<String> excludes) {
        this.fetchSource = fetchSource;
        this.includes = includes;
        this.excludes = excludes;
    }

    public static FetchSourceContext parse(Object value) {
        if (value instanceof Boolean b) {
            return b ? FETCH_SOURCE : DO_NOT_FETCH_SOURCE;
        }
        if (value instanceof String s) {
            return new FetchSourceContext(true, List.of(s), List.of());
        }
        if (value instanceof List<?>) {
            return new FetchSourceContext(true, QueryParseUtils.asStringList(value), List.of());
        }
        Map<String, Object> map = QueryParseUtils.asMap(value, "_source");
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        Object inc = map.get("includes");
        if (inc == null) {
            inc = map.get("include");
        }
        if (inc != null) {
            includes.addAll(QueryParseUtils.asStringList(inc));
        }
        Object exc = map.get("excludes");
        if (exc == null) {
            exc = map.get("exclude");
        }
        if (exc != null) {
            excludes.addAll(QueryParseUtils.asStringList(exc));
        }
        return new FetchSourceContext(true, includes, excludes);
    }

    public boolean fetchSource() {
        return fetchSource;
    }

    public List<String> includes() {
        return includes;
    }

    public List<String> excludes() {
        return excludes;
    }

    public Object toMapOrBoolean() {
        if (includes.isEmpty() && excludes.isEmpty()) {
            return fetchSource;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        if (!includes.isEmpty()) {
            m.put("includes", includes);
        }
        if (!excludes.isEmpty()) {
            m.put("excludes", excludes);
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FetchSourceContext other)) {
            return false;
        }
        return fetchSource == other.fetchSource && includes.equals(other.includes) && excludes.equals(other.excludes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fetchSource, includes, excludes);
    }
}
