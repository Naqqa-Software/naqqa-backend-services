package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class SpanNearQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_near";

    private final List<SpanQueryBuilder> clauses = new ArrayList<>();
    private int slop = 0;
    private boolean inOrder = true;

    public SpanNearQueryBuilder addClause(SpanQueryBuilder clause) {
        clauses.add(clause);
        return this;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("clauses", "slop", "in_order", "boost", "_name");

    public static SpanNearQueryBuilder fromMap(Map<String, Object> value) {
        Object clauses = value.remove("clauses");
        if (clauses == null) {
            throw QueryParseUtils.error("[{}] requires [clauses]", NAME);
        }
        SpanNearQueryBuilder builder = new SpanNearQueryBuilder();
        for (Object o : QueryParseUtils.asList(clauses, NAME)) {
            builder.clauses.add(SpanQueryParseUtils.parseSpan(o, NAME));
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "slop" -> builder.slop = QueryParseUtils.asInt(e.getValue());
                case "in_order" -> builder.inOrder = QueryParseUtils.asBoolean(e.getValue());
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

    public int slop() {
        return slop;
    }

    public boolean inOrder() {
        return inOrder;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("clauses", clauses.stream().map(SpanQueryBuilder::toMap).collect(Collectors.toList()));
        if (slop != 0) {
            inner.put("slop", slop);
        }
        if (!inOrder) {
            inner.put("in_order", false);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SpanNearQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && clauses.equals(other.clauses) && slop == other.slop && inOrder == other.inOrder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), clauses, slop, inOrder);
    }
}
