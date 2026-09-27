package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.index.query.QueryParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class NestedSortBuilder {

    private final String path;
    private QueryBuilder filter;
    private Integer maxChildren;
    private NestedSortBuilder nested;

    public NestedSortBuilder(String path) {
        this.path = Objects.requireNonNull(path);
    }

    @SuppressWarnings("unchecked")
    public static NestedSortBuilder fromMap(Map<String, Object> map) {
        Object path = map.get("path");
        if (path == null) {
            throw QueryParseUtils.error("nested sort requires a [path]");
        }
        NestedSortBuilder builder = new NestedSortBuilder(QueryParseUtils.asString(path));
        Object filter = map.get("filter");
        if (filter != null) {
            builder.filter = QueryParser.parseQuery((Map<String, Object>) filter);
        }
        Object maxChildren = map.get("max_children");
        if (maxChildren != null) {
            builder.maxChildren = QueryParseUtils.asInt(maxChildren);
        }
        Object nested = map.get("nested");
        if (nested != null) {
            builder.nested = fromMap(QueryParseUtils.asMap(nested, "nested"));
        }
        return builder;
    }

    public String path() {
        return path;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        if (filter != null) {
            m.put("filter", filter.toMap());
        }
        if (maxChildren != null) {
            m.put("max_children", maxChildren);
        }
        if (nested != null) {
            m.put("nested", nested.toMap());
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NestedSortBuilder other)) {
            return false;
        }
        return path.equals(other.path) && Objects.equals(filter, other.filter) && Objects.equals(maxChildren, other.maxChildren) && Objects.equals(nested, other.nested);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, filter, maxChildren, nested);
    }
}
