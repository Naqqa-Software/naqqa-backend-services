package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TermQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "term";

    private final String fieldName;
    private final Object value;
    private boolean caseInsensitive = false;

    public TermQueryBuilder(String fieldName, Object value) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.value = Objects.requireNonNull(value);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("value", "case_insensitive", "boost", "_name");

    public static TermQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        String fieldName = field.getKey();
        Object raw = field.getValue();
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(raw, NAME);
            Object v = params.remove("value");
            if (v == null) {
                throw QueryParseUtils.error("[{}] query requires a [value]", NAME);
            }
            TermQueryBuilder builder = new TermQueryBuilder(fieldName, v);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                switch (e.getKey()) {
                    case "case_insensitive" -> builder.caseInsensitive = QueryParseUtils.asBoolean(e.getValue());
                    case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                    default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
                }
            }
            return builder;
        }
        return new TermQueryBuilder(fieldName, raw);
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

    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    public void caseInsensitive(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", value);
        if (caseInsensitive) {
            params.put("case_insensitive", true);
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TermQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && value.equals(other.value) && caseInsensitive == other.caseInsensitive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, value, caseInsensitive);
    }
}
