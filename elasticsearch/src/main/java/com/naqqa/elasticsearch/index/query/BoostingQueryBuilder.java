package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BoostingQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "boosting";

    private final QueryBuilder positive;
    private final QueryBuilder negative;
    private float negativeBoost = 1.0f;

    public BoostingQueryBuilder(QueryBuilder positive, QueryBuilder negative) {
        this.positive = Objects.requireNonNull(positive);
        this.negative = Objects.requireNonNull(negative);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("positive", "negative", "negative_boost", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static BoostingQueryBuilder fromMap(Map<String, Object> value) {
        Object positive = value.remove("positive");
        Object negative = value.remove("negative");
        if (positive == null || negative == null) {
            throw QueryParseUtils.error("[{}] query requires both [positive] and [negative] queries", NAME);
        }
        BoostingQueryBuilder builder = new BoostingQueryBuilder(
            QueryParser.parseQuery((Map<String, Object>) positive),
            QueryParser.parseQuery((Map<String, Object>) negative));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "negative_boost" -> builder.negativeBoost = QueryParseUtils.asFloat(e.getValue());
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

    public QueryBuilder positive() {
        return positive;
    }

    public QueryBuilder negative() {
        return negative;
    }

    public float negativeBoost() {
        return negativeBoost;
    }

    public void negativeBoost(float negativeBoost) {
        this.negativeBoost = negativeBoost;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("positive", positive.toMap());
        inner.put("negative", negative.toMap());
        inner.put("negative_boost", negativeBoost);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BoostingQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && positive.equals(other.positive) && negative.equals(other.negative) && negativeBoost == other.negativeBoost;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), positive, negative, negativeBoost);
    }
}
