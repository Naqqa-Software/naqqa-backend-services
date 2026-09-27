package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HasParentQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "has_parent";

    private final String parentType;
    private final QueryBuilder query;
    private boolean score = false;
    private boolean ignoreUnmapped = false;
    private Map<String, Object> innerHits;

    public HasParentQueryBuilder(String parentType, QueryBuilder query) {
        this.parentType = Objects.requireNonNull(parentType);
        this.query = Objects.requireNonNull(query);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("parent_type", "query", "score", "ignore_unmapped", "inner_hits", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static HasParentQueryBuilder fromMap(Map<String, Object> value) {
        Object parentType = value.remove("parent_type");
        Object query = value.remove("query");
        if (parentType == null || query == null) {
            throw QueryParseUtils.error("[{}] requires both [parent_type] and [query]", NAME);
        }
        HasParentQueryBuilder builder = new HasParentQueryBuilder(QueryParseUtils.asString(parentType), QueryParser.parseQuery((Map<String, Object>) query));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "score" -> builder.score = QueryParseUtils.asBoolean(e.getValue());
                case "ignore_unmapped" -> builder.ignoreUnmapped = QueryParseUtils.asBoolean(e.getValue());
                case "inner_hits" -> builder.innerHits = QueryParseUtils.asMap(e.getValue(), NAME);
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

    public String parentType() {
        return parentType;
    }

    public QueryBuilder query() {
        return query;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("parent_type", parentType);
        inner.put("query", query.toMap());
        if (score) {
            inner.put("score", true);
        }
        if (ignoreUnmapped) {
            inner.put("ignore_unmapped", true);
        }
        if (innerHits != null) {
            inner.put("inner_hits", innerHits);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HasParentQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && parentType.equals(other.parentType) && query.equals(other.query) && score == other.score
            && ignoreUnmapped == other.ignoreUnmapped && Objects.equals(innerHits, other.innerHits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), parentType, query, score, ignoreUnmapped, innerHits);
    }
}
