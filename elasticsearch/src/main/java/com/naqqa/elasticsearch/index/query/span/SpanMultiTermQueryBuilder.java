package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.index.query.QueryParser;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpanMultiTermQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_multi";

    private final QueryBuilder match;

    public SpanMultiTermQueryBuilder(QueryBuilder match) {
        this.match = Objects.requireNonNull(match);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("match", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static SpanMultiTermQueryBuilder fromMap(Map<String, Object> value) {
        Object match = value.remove("match");
        if (match == null) {
            throw QueryParseUtils.error("[{}] requires a [match] query", NAME);
        }
        SpanMultiTermQueryBuilder builder = new SpanMultiTermQueryBuilder(QueryParser.parseQuery((Map<String, Object>) match));
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

    public QueryBuilder match() {
        return match;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("match", match.toMap());
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SpanMultiTermQueryBuilder other && commonEquals(other) && match.equals(other.match);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), match);
    }
}
