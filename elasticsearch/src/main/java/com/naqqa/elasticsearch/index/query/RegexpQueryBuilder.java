package com.naqqa.elasticsearch.index.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RegexpQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "regexp";

    private final String fieldName;
    private final String value;
    private String flags = "ALL";
    private int maxDeterminizedStates = 10000;
    private String rewrite;
    private boolean caseInsensitive = false;

    public RegexpQueryBuilder(String fieldName, String value) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.value = Objects.requireNonNull(value);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("value", "flags", "max_determinized_states", "rewrite", "case_insensitive", "boost", "_name");

    public static RegexpQueryBuilder fromMap(Map<String, Object> value) {
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Object raw = field.getValue();
        if (raw instanceof Map<?, ?>) {
            Map<String, Object> params = QueryParseUtils.asMap(raw, NAME);
            Object v = params.remove("value");
            if (v == null) {
                throw QueryParseUtils.error("[{}] query requires a [value]", NAME);
            }
            RegexpQueryBuilder builder = new RegexpQueryBuilder(field.getKey(), QueryParseUtils.asString(v));
            for (Map.Entry<String, Object> e : params.entrySet()) {
                switch (e.getKey()) {
                    case "flags" -> builder.flags = QueryParseUtils.asString(e.getValue());
                    case "max_determinized_states" -> builder.maxDeterminizedStates = QueryParseUtils.asInt(e.getValue());
                    case "rewrite" -> builder.rewrite = QueryParseUtils.asString(e.getValue());
                    case "case_insensitive" -> builder.caseInsensitive = QueryParseUtils.asBoolean(e.getValue());
                    case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                    default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
                }
            }
            return builder;
        }
        return new RegexpQueryBuilder(field.getKey(), QueryParseUtils.asString(raw));
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
        if (!"ALL".equals(flags)) {
            params.put("flags", flags);
        }
        if (maxDeterminizedStates != 10000) {
            params.put("max_determinized_states", maxDeterminizedStates);
        }
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
        if (!(o instanceof RegexpQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && value.equals(other.value) && flags.equals(other.flags)
            && maxDeterminizedStates == other.maxDeterminizedStates && Objects.equals(rewrite, other.rewrite) && caseInsensitive == other.caseInsensitive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, value, flags, maxDeterminizedStates, rewrite, caseInsensitive);
    }
}
