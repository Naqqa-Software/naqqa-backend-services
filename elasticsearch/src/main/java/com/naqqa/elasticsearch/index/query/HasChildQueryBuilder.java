package com.naqqa.elasticsearch.index.query;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HasChildQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "has_child";

    public enum ScoreMode {
        NONE, AVG, SUM, MAX, MIN;

        static ScoreMode fromString(String s) {
            return ScoreMode.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String type;
    private final QueryBuilder query;
    private ScoreMode scoreMode = ScoreMode.NONE;
    private Integer minChildren;
    private Integer maxChildren;
    private boolean ignoreUnmapped = false;
    private Map<String, Object> innerHits;

    public HasChildQueryBuilder(String type, QueryBuilder query) {
        this.type = Objects.requireNonNull(type);
        this.query = Objects.requireNonNull(query);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("type", "query", "score_mode", "min_children", "max_children",
        "ignore_unmapped", "inner_hits", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static HasChildQueryBuilder fromMap(Map<String, Object> value) {
        Object type = value.remove("type");
        Object query = value.remove("query");
        if (type == null || query == null) {
            throw QueryParseUtils.error("[{}] requires both [type] and [query]", NAME);
        }
        HasChildQueryBuilder builder = new HasChildQueryBuilder(QueryParseUtils.asString(type), QueryParser.parseQuery((Map<String, Object>) query));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "score_mode" -> builder.scoreMode = ScoreMode.fromString(QueryParseUtils.asString(e.getValue()));
                case "min_children" -> builder.minChildren = QueryParseUtils.asInt(e.getValue());
                case "max_children" -> builder.maxChildren = QueryParseUtils.asInt(e.getValue());
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

    public String type() {
        return type;
    }

    public QueryBuilder query() {
        return query;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("type", type);
        inner.put("query", query.toMap());
        if (scoreMode != ScoreMode.NONE) {
            inner.put("score_mode", scoreMode.toValue());
        }
        if (minChildren != null) {
            inner.put("min_children", minChildren);
        }
        if (maxChildren != null) {
            inner.put("max_children", maxChildren);
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
        if (!(o instanceof HasChildQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && type.equals(other.type) && query.equals(other.query) && scoreMode == other.scoreMode
            && Objects.equals(minChildren, other.minChildren) && Objects.equals(maxChildren, other.maxChildren)
            && ignoreUnmapped == other.ignoreUnmapped && Objects.equals(innerHits, other.innerHits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), type, query, scoreMode, minChildren, maxChildren, ignoreUnmapped, innerHits);
    }
}
