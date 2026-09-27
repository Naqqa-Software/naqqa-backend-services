package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.script.Script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ScriptSortBuilder implements SortBuilder {

    private final Script script;
    private final String type;
    private SortOrder order = SortOrder.ASC;
    private SortMode mode;
    private NestedSortBuilder nested;

    public ScriptSortBuilder(Script script, String type) {
        this.script = Objects.requireNonNull(script);
        this.type = Objects.requireNonNull(type);
    }

    public static ScriptSortBuilder fromMap(Map<String, Object> params) {
        Object script = params.remove("script");
        Object type = params.remove("type");
        if (script == null || type == null) {
            throw QueryParseUtils.error("script sort requires [script] and [type]");
        }
        ScriptSortBuilder builder = new ScriptSortBuilder(Script.parse(script), QueryParseUtils.asString(type));
        Object order = params.remove("order");
        if (order != null) {
            builder.order = SortOrder.fromString(QueryParseUtils.asString(order));
        }
        Object mode = params.remove("mode");
        if (mode != null) {
            builder.mode = SortMode.fromString(QueryParseUtils.asString(mode));
        }
        Object nested = params.remove("nested");
        if (nested != null) {
            builder.nested = NestedSortBuilder.fromMap(QueryParseUtils.asMap(nested, "nested"));
        }
        return builder;
    }

    public Script script() {
        return script;
    }

    public String type() {
        return type;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("script", script.toMap());
        params.put("type", type);
        if (order != SortOrder.ASC) {
            params.put("order", order.toValue());
        }
        if (mode != null) {
            params.put("mode", mode.toValue());
        }
        if (nested != null) {
            params.put("nested", nested.toMap());
        }
        return Map.of("_script", params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScriptSortBuilder other)) {
            return false;
        }
        return script.equals(other.script) && type.equals(other.type) && order == other.order && mode == other.mode && Objects.equals(nested, other.nested);
    }

    @Override
    public int hashCode() {
        return Objects.hash(script, type, order, mode, nested);
    }
}
