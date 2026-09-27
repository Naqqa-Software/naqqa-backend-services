package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class DisMaxQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "dis_max";

    private final List<QueryBuilder> queries = new ArrayList<>();
    private Float tieBreaker;

    public DisMaxQueryBuilder add(QueryBuilder query) {
        queries.add(query);
        return this;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("queries", "tie_breaker", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static DisMaxQueryBuilder fromMap(Map<String, Object> value) {
        DisMaxQueryBuilder builder = new DisMaxQueryBuilder();
        Object queries = value.remove("queries");
        if (queries != null) {
            for (Object o : QueryParseUtils.asList(queries, NAME)) {
                builder.queries.add(QueryParser.parseQuery((Map<String, Object>) o));
            }
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "tie_breaker" -> builder.tieBreaker = QueryParseUtils.asFloat(e.getValue());
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

    public List<QueryBuilder> queries() {
        return queries;
    }

    public Float tieBreaker() {
        return tieBreaker;
    }

    public void tieBreaker(Float tieBreaker) {
        this.tieBreaker = tieBreaker;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("queries", queries.stream().map(QueryBuilder::toMap).collect(Collectors.toList()));
        if (tieBreaker != null) {
            inner.put("tie_breaker", tieBreaker);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DisMaxQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && queries.equals(other.queries) && Objects.equals(tieBreaker, other.tieBreaker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), queries, tieBreaker);
    }
}
