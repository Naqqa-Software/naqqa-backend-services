package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpanTermQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_term";

    private final String fieldName;
    private final Object value;

    public SpanTermQueryBuilder(String fieldName, Object value) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.value = Objects.requireNonNull(value);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("value", "boost", "_name");

    public static SpanTermQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Object raw = field.getValue();
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(raw, NAME);
            Object v = params.remove("value");
            if (v == null) {
                throw QueryParseUtils.error("[{}] requires a [value]", NAME);
            }
            SpanTermQueryBuilder builder = new SpanTermQueryBuilder(field.getKey(), v);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                switch (e.getKey()) {
                    case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                    default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
                }
            }
            return builder;
        }
        return new SpanTermQueryBuilder(field.getKey(), raw);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public Object value() {
        return value;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", value);
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SpanTermQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, value);
    }
}
