package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.script.Script;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ScriptScoreQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "script_score";

    private final QueryBuilder query;
    private final Script script;
    private Float minScore;

    public ScriptScoreQueryBuilder(QueryBuilder query, Script script) {
        this.query = Objects.requireNonNull(query);
        this.script = Objects.requireNonNull(script);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("query", "script", "min_score", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static ScriptScoreQueryBuilder fromMap(Map<String, Object> value) {
        Object query = value.remove("query");
        Object script = value.remove("script");
        if (query == null || script == null) {
            throw QueryParseUtils.error("[{}] requires both [query] and [script]", NAME);
        }
        ScriptScoreQueryBuilder builder = new ScriptScoreQueryBuilder(QueryParser.parseQuery((Map<String, Object>) query), Script.parse(script));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "min_score" -> builder.minScore = QueryParseUtils.asFloat(e.getValue());
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

    public QueryBuilder query() {
        return query;
    }

    public Script script() {
        return script;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("query", query.toMap());
        inner.put("script", script.toMap());
        if (minScore != null) {
            inner.put("min_score", minScore);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScriptScoreQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && query.equals(other.query) && script.equals(other.script) && Objects.equals(minScore, other.minScore);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), query, script, minScore);
    }
}
