package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class TermsLookup {

    private final String index;
    private final String id;
    private final String path;
    private String routing;

    public TermsLookup(String index, String id, String path) {
        this.index = index;
        this.id = id;
        this.path = path;
    }

    public static TermsLookup fromMap(Map<String, Object> map) {
        TermsLookup lookup = new TermsLookup(
            QueryParseUtils.asString(map.get("index")),
            QueryParseUtils.asString(map.get("id")),
            QueryParseUtils.asString(map.get("path")));
        Object routing = map.get("routing");
        if (routing != null) {
            lookup.routing = QueryParseUtils.asString(routing);
        }
        return lookup;
    }

    public String index() {
        return index;
    }

    public String id() {
        return id;
    }

    public String path() {
        return path;
    }

    public String routing() {
        return routing;
    }

    public void routing(String routing) {
        this.routing = routing;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        m.put("id", id);
        m.put("path", path);
        if (routing != null) {
            m.put("routing", routing);
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TermsLookup other)) {
            return false;
        }
        return Objects.equals(index, other.index) && Objects.equals(id, other.id) && Objects.equals(path, other.path) && Objects.equals(routing, other.routing);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, id, path, routing);
    }
}
