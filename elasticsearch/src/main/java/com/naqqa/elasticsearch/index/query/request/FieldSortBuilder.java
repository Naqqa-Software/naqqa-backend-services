package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FieldSortBuilder implements SortBuilder {

    private final String fieldName;
    private SortOrder order = SortOrder.ASC;
    private SortMode mode;
    private Object missing;
    private String unmappedType;
    private NestedSortBuilder nested;

    public FieldSortBuilder(String fieldName) {
        this.fieldName = Objects.requireNonNull(fieldName);
    }

    public static FieldSortBuilder fromScalar(String fieldName, String orderOrKeyword) {
        FieldSortBuilder builder = new FieldSortBuilder(fieldName);
        if (orderOrKeyword != null) {
            builder.order = SortOrder.fromString(orderOrKeyword);
        }
        return builder;
    }

    public static FieldSortBuilder fromMap(String fieldName, Map<String, Object> params) {
        FieldSortBuilder builder = new FieldSortBuilder(fieldName);
        Object order = params.remove("order");
        if (order != null) {
            builder.order = SortOrder.fromString(QueryParseUtils.asString(order));
        }
        Object mode = params.remove("mode");
        if (mode != null) {
            builder.mode = SortMode.fromString(QueryParseUtils.asString(mode));
        }
        builder.missing = params.remove("missing");
        Object unmappedType = params.remove("unmapped_type");
        if (unmappedType != null) {
            builder.unmappedType = QueryParseUtils.asString(unmappedType);
        }
        Object nested = params.remove("nested");
        if (nested != null) {
            builder.nested = NestedSortBuilder.fromMap(QueryParseUtils.asMap(nested, "nested"));
        }
        return builder;
    }

    public String fieldName() {
        return fieldName;
    }

    public SortOrder order() {
        return order;
    }

    public void order(SortOrder order) {
        this.order = order;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        if (order != SortOrder.ASC) {
            params.put("order", order.toValue());
        }
        if (mode != null) {
            params.put("mode", mode.toValue());
        }
        if (missing != null) {
            params.put("missing", missing);
        }
        if (unmappedType != null) {
            params.put("unmapped_type", unmappedType);
        }
        if (nested != null) {
            params.put("nested", nested.toMap());
        }
        if (params.isEmpty()) {
            return Map.of(fieldName, order.toValue());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(fieldName, params);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FieldSortBuilder other)) {
            return false;
        }
        return fieldName.equals(other.fieldName) && order == other.order && mode == other.mode && Objects.equals(missing, other.missing)
            && Objects.equals(unmappedType, other.unmappedType) && Objects.equals(nested, other.nested);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, order, mode, missing, unmappedType, nested);
    }
}
