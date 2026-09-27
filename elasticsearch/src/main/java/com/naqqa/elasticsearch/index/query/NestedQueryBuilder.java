package com.naqqa.elasticsearch.index.query;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class NestedQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "nested";

    public enum ScoreMode {
        AVG, SUM, MIN, MAX, NONE;

        static ScoreMode fromString(String s) {
            return ScoreMode.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String path;
    private final QueryBuilder query;
    private ScoreMode scoreMode = ScoreMode.AVG;
    private boolean ignoreUnmapped = false;
    private Map<String, Object> innerHits;

    public NestedQueryBuilder(String path, QueryBuilder query) {
        this.path = Objects.requireNonNull(path);
        this.query = Objects.requireNonNull(query);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("path", "query", "score_mode", "ignore_unmapped", "inner_hits", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static NestedQueryBuilder fromMap(Map<String, Object> value) {
        Object path = value.remove("path");
        Object query = value.remove("query");
        if (path == null || query == null) {
            throw QueryParseUtils.error("[{}] requires both [path] and [query]", NAME);
        }
        NestedQueryBuilder builder = new NestedQueryBuilder(QueryParseUtils.asString(path), QueryParser.parseQuery((Map<String, Object>) query));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "score_mode" -> builder.scoreMode = ScoreMode.fromString(QueryParseUtils.asString(e.getValue()));
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

    public String path() {
        return path;
    }

    public QueryBuilder query() {
        return query;
    }

    public ScoreMode scoreMode() {
        return scoreMode;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("path", path);
        inner.put("query", query.toMap());
        if (scoreMode != ScoreMode.AVG) {
            inner.put("score_mode", scoreMode.toValue());
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
        if (!(o instanceof NestedQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && path.equals(other.path) && query.equals(other.query) && scoreMode == other.scoreMode
            && ignoreUnmapped == other.ignoreUnmapped && Objects.equals(innerHits, other.innerHits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), path, query, scoreMode, ignoreUnmapped, innerHits);
    }
}
