package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Set;

public final class MatchNoneQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "match_none";

    private static final Set<String> KNOWN_FIELDS = Set.of("boost", "_name");

    public static MatchNoneQueryBuilder fromMap(Map<String, Object> value) {
        MatchNoneQueryBuilder builder = new MatchNoneQueryBuilder();
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
        return o instanceof MatchNoneQueryBuilder other && commonEquals(other);
    }

    @Override
    public int hashCode() {
        return commonHash();
    }
}
