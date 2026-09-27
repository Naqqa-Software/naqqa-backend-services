package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Set;

public final class MatchAllQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "match_all";

    private static final Set<String> KNOWN_FIELDS = Set.of("boost", "_name");

    public static MatchAllQueryBuilder fromMap(Map<String, Object> value) {
        MatchAllQueryBuilder builder = new MatchAllQueryBuilder();
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

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MatchAllQueryBuilder other && commonEquals(other);
    }

    @Override
    public int hashCode() {
        return commonHash();
    }
}
