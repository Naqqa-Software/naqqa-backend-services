package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class IdsQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "ids";

    private final List<String> values;

    public IdsQueryBuilder(List<String> values) {
        this.values = new ArrayList<>(Objects.requireNonNull(values));
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("values", "boost", "_name");

    public static IdsQueryBuilder fromMap(Map<String, Object> value) {
        Object values = value.remove("values");
        IdsQueryBuilder builder = new IdsQueryBuilder(values == null ? List.of() : QueryParseUtils.asStringList(values));
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

    public List<String> values() {
        return values;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("values", values);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof IdsQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), values);
    }
}
