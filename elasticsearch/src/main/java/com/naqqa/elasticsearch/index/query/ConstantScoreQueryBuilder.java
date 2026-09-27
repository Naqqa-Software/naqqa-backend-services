package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ConstantScoreQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "constant_score";

    private final QueryBuilder filter;

    public ConstantScoreQueryBuilder(QueryBuilder filter) {
        this.filter = Objects.requireNonNull(filter);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("filter", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static ConstantScoreQueryBuilder fromMap(Map<String, Object> value) {
        Object filter = value.remove("filter");
        if (filter == null) {
            throw QueryParseUtils.error("[{}] requires a [filter]", NAME);
        }
        ConstantScoreQueryBuilder builder = new ConstantScoreQueryBuilder(QueryParser.parseQuery((Map<String, Object>) filter));
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

    public QueryBuilder innerQuery() {
        return filter;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("filter", filter.toMap());
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ConstantScoreQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && filter.equals(other.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), filter);
    }
}
