package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PrefixQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "prefix";

    private final String fieldName;
    private final String value;
    private String rewrite;
    private boolean caseInsensitive = false;

    public PrefixQueryBuilder(String fieldName, String value) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.value = Objects.requireNonNull(value);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("value", "rewrite", "case_insensitive", "boost", "_name");

    public static PrefixQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Object raw = field.getValue();
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(raw, NAME);
            Object v = params.remove("value");
            if (v == null) {
                throw QueryParseUtils.error("[{}] query requires a [value]", NAME);
            }
            PrefixQueryBuilder builder = new PrefixQueryBuilder(field.getKey(), QueryParseUtils.asString(v));
            for (Map.Entry<String, Object> e : params.entrySet()) {
                switch (e.getKey()) {
                    case "rewrite" -> builder.rewrite = QueryParseUtils.asString(e.getValue());
                    case "case_insensitive" -> builder.caseInsensitive = QueryParseUtils.asBoolean(e.getValue());
                    case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                    default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
                }
            }
            return builder;
        }
        return new PrefixQueryBuilder(field.getKey(), QueryParseUtils.asString(raw));
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public String value() {
        return value;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("value", value);
        if (rewrite != null) {
            params.put("rewrite", rewrite);
        }
        if (caseInsensitive) {
            params.put("case_insensitive", true);
        }
        writeCommon(params);
        inner.put(fieldName, params);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PrefixQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && value.equals(other.value)
            && Objects.equals(rewrite, other.rewrite) && caseInsensitive == other.caseInsensitive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, value, rewrite, caseInsensitive);
    }
}
