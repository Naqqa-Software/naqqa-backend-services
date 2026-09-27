package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpanFirstQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_first";

    private final SpanQueryBuilder match;
    private final int end;

    public SpanFirstQueryBuilder(SpanQueryBuilder match, int end) {
        this.match = Objects.requireNonNull(match);
        this.end = end;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("match", "end", "boost", "_name");

    public static SpanFirstQueryBuilder fromMap(Map<String, Object> value) {
        Object match = value.remove("match");
        Object end = value.remove("end");
        if (match == null || end == null) {
            throw QueryParseUtils.error("[{}] requires both [match] and [end]", NAME);
        }
        SpanFirstQueryBuilder builder = new SpanFirstQueryBuilder(SpanQueryParseUtils.parseSpan(match, NAME), QueryParseUtils.asInt(end));
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

    public SpanQueryBuilder match() {
        return match;
    }

    public int end() {
        return end;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("match", match.toMap());
        inner.put("end", end);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SpanFirstQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && match.equals(other.match) && end == other.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), match, end);
    }
}
