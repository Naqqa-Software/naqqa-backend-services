package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ExistsQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "exists";

    private final String fieldName;

    public ExistsQueryBuilder(String fieldName) {
        this.fieldName = Objects.requireNonNull(fieldName);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("field", "boost", "_name");

    public static ExistsQueryBuilder fromMap(Map<String, Object> value) {
        Object field = value.remove("field");
        if (field == null) {
            throw QueryParseUtils.error("[{}] requires a [field]", NAME);
        }
        ExistsQueryBuilder builder = new ExistsQueryBuilder(QueryParseUtils.asString(field));
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

    public String fieldName() {
        return fieldName;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("field", fieldName);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ExistsQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName);
    }
}
