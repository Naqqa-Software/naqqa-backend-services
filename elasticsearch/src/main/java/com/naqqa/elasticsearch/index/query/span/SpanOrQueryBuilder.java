package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SpanOrQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_or";

    private final List<SpanQueryBuilder> clauses = new ArrayList<>();

    public SpanOrQueryBuilder addClause(SpanQueryBuilder clause) {
        clauses.add(clause);
        return this;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("clauses", "boost", "_name");

    public static SpanOrQueryBuilder fromMap(Map<String, Object> value) {
        Object clauses = value.remove("clauses");
        if (clauses == null) {
            throw QueryParseUtils.error("[{}] requires [clauses]", NAME);
        }
        SpanOrQueryBuilder builder = new SpanOrQueryBuilder();
        for (Object o : QueryParseUtils.asList(clauses, NAME)) {
            builder.clauses.add(SpanQueryParseUtils.parseSpan(o, NAME));
        }
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

    public List<SpanQueryBuilder> clauses() {
        return clauses;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("clauses", clauses.stream().map(SpanQueryBuilder::toMap).collect(Collectors.toList()));
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SpanOrQueryBuilder other && commonEquals(other) && clauses.equals(other.clauses);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(commonHash(), clauses);
    }
}
