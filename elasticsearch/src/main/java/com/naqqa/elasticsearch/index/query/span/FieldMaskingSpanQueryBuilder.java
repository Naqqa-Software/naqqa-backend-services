package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FieldMaskingSpanQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "field_masking_span";

    private final SpanQueryBuilder query;
    private final String field;

    public FieldMaskingSpanQueryBuilder(SpanQueryBuilder query, String field) {
        this.query = Objects.requireNonNull(query);
        this.field = Objects.requireNonNull(field);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "field", "boost", "_name");

    public static FieldMaskingSpanQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        Object field = value.remove("field");
        if (query == null || field == null) {
            throw QueryParseUtils.error("[{}] requires both [query] and [field]", NAME);
        }
        FieldMaskingSpanQueryBuilder builder = new FieldMaskingSpanQueryBuilder(SpanQueryParseUtils.parseSpan(query, NAME), QueryParseUtils.asString(field));
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

    public SpanQueryBuilder query() {
        return query;
    }

    public String field() {
        return field;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("query", query.toMap());
        inner.put("field", field);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FieldMaskingSpanQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && field.equals(other.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, field);
    }
}
