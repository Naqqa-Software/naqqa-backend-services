package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpanContainingQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_containing";

    private final SpanQueryBuilder little;
    private final SpanQueryBuilder big;

    public SpanContainingQueryBuilder(SpanQueryBuilder little, SpanQueryBuilder big) {
        this.little = Objects.requireNonNull(little);
        this.big = Objects.requireNonNull(big);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("little", "big", "boost", "_name");

    public static SpanContainingQueryBuilder fromMap(Map<String, Object> value) {
        Object little = value.remove("little");
        Object big = value.remove("big");
        if (little == null || big == null) {
            throw QueryParseUtils.error("[{}] requires both [little] and [big]", NAME);
        }
        SpanContainingQueryBuilder builder = new SpanContainingQueryBuilder(
            SpanQueryParseUtils.parseSpan(little, NAME), SpanQueryParseUtils.parseSpan(big, NAME));
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

    public SpanQueryBuilder little() {
        return little;
    }

    public SpanQueryBuilder big() {
        return big;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("little", little.toMap());
        inner.put("big", big.toMap());
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SpanContainingQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && little.equals(other.little) && big.equals(other.big);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), little, big);
    }
}
