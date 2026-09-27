package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class BoolQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "bool";

    private final List<QueryBuilder> must = new ArrayList<>();
    private final List<QueryBuilder> filter = new ArrayList<>();
    private final List<QueryBuilder> should = new ArrayList<>();
    private final List<QueryBuilder> mustNot = new ArrayList<>();
    private MinimumShouldMatch minimumShouldMatch;

    public BoolQueryBuilder must(QueryBuilder q) {
        must.add(q);
        return this;
    }

    public BoolQueryBuilder filter(QueryBuilder q) {
        filter.add(q);
        return this;
    }

    public BoolQueryBuilder should(QueryBuilder q) {
        should.add(q);
        return this;
    }

    public BoolQueryBuilder mustNot(QueryBuilder q) {
        mustNot.add(q);
        return this;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("must", "filter", "should", "must_not", "minimum_should_match", "boost", "_name");

    public static BoolQueryBuilder fromMap(Map<String, Object> value) {
        BoolQueryBuilder builder = new BoolQueryBuilder();
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "must" -> builder.must.addAll(parseClauses(v));
                case "filter" -> builder.filter.addAll(parseClauses(v));
                case "should" -> builder.should.addAll(parseClauses(v));
                case "must_not" -> builder.mustNot.addAll(parseClauses(v));
                case "minimum_should_match" -> builder.minimumShouldMatch = MinimumShouldMatch.of(v);
                case "boost", "_name" -> builder.readCommon(key, v);
                default -> throw QueryParseUtils.unknownField(NAME, key, KNOWN_FIELDS);
            }
        }
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static List<QueryBuilder> parseClauses(Object value) {
        List<QueryBuilder> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                result.add(QueryParser.parseQuery((Map<String, Object>) o));
            }
        } else {
            result.add(QueryParser.parseQuery((Map<String, Object>) value));
        }
        return result;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public List<QueryBuilder> must() {
        return must;
    }

    public List<QueryBuilder> filter() {
        return filter;
    }

    public List<QueryBuilder> should() {
        return should;
    }

    public List<QueryBuilder> mustNot() {
        return mustNot;
    }

    public MinimumShouldMatch minimumShouldMatch() {
        return minimumShouldMatch;
    }

    public void minimumShouldMatch(MinimumShouldMatch minimumShouldMatch) {
        this.minimumShouldMatch = minimumShouldMatch;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        if (!must.isEmpty()) {
            inner.put("must", must.stream().map(QueryBuilder::toMap).collect(Collectors.toList()));
        }
        if (!filter.isEmpty()) {
            inner.put("filter", filter.stream().map(QueryBuilder::toMap).collect(Collectors.toList()));
        }
        if (!should.isEmpty()) {
            inner.put("should", should.stream().map(QueryBuilder::toMap).collect(Collectors.toList()));
        }
        if (!mustNot.isEmpty()) {
            inner.put("must_not", mustNot.stream().map(QueryBuilder::toMap).collect(Collectors.toList()));
        }
        if (minimumShouldMatch != null) {
            inner.put("minimum_should_match", minimumShouldMatch.asString());
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BoolQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && must.equals(other.must) && filter.equals(other.filter) && should.equals(other.should)
            && mustNot.equals(other.mustNot) && Objects.equals(minimumShouldMatch, other.minimumShouldMatch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), must, filter, should, mustNot, minimumShouldMatch);
    }
}
