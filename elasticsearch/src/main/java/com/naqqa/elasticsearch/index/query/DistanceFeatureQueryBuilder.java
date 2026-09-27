package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DistanceFeatureQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "distance_feature";

    private final String field;
    private final Object origin;
    private final String pivot;

    public DistanceFeatureQueryBuilder(String field, Object origin, String pivot) {
        this.field = Objects.requireNonNull(field);
        this.origin = Objects.requireNonNull(origin);
        this.pivot = Objects.requireNonNull(pivot);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("field", "origin", "pivot", "boost", "_name");

    public static DistanceFeatureQueryBuilder fromMap(Map<String, Object> value) {
        Object field = value.remove("field");
        Object origin = value.remove("origin");
        Object pivot = value.remove("pivot");
        if (field == null || origin == null || pivot == null) {
            throw QueryParseUtils.error("[{}] requires [field], [origin] and [pivot]", NAME);
        }
        DistanceFeatureQueryBuilder builder = new DistanceFeatureQueryBuilder(QueryParseUtils.asString(field), origin, QueryParseUtils.asString(pivot));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String field() {
        return field;
    }

    public Object origin() {
        return origin;
    }

    public String pivot() {
        return pivot;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("field", field);
        inner.put("origin", origin);
        inner.put("pivot", pivot);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DistanceFeatureQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && field.equals(other.field) && origin.equals(other.origin) && pivot.equals(other.pivot);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), field, origin, pivot);
    }
}
