package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SpanNotQueryBuilder extends AbstractQueryBuilder implements SpanQueryBuilder {

    public static final String NAME = "span_not";

    private final SpanQueryBuilder include;
    private final SpanQueryBuilder exclude;
    private Integer pre;
    private Integer post;
    private Integer dist;

    public SpanNotQueryBuilder(SpanQueryBuilder include, SpanQueryBuilder exclude) {
        this.include = Objects.requireNonNull(include);
        this.exclude = Objects.requireNonNull(exclude);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("include", "exclude", "pre", "post", "dist", "boost", "_name");

    public static SpanNotQueryBuilder fromMap(Map<String, Object> value) {
        Object include = value.remove("include");
        Object exclude = value.remove("exclude");
        if (include == null || exclude == null) {
            throw QueryParseUtils.error("[{}] requires both [include] and [exclude]", NAME);
        }
        SpanNotQueryBuilder builder = new SpanNotQueryBuilder(
            SpanQueryParseUtils.parseSpan(include, NAME),
            SpanQueryParseUtils.parseSpan(exclude, NAME));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "pre" -> builder.pre = QueryParseUtils.asInt(e.getValue());
                case "post" -> builder.post = QueryParseUtils.asInt(e.getValue());
                case "dist" -> builder.dist = QueryParseUtils.asInt(e.getValue());
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

    public SpanQueryBuilder include() {
        return include;
    }

    public SpanQueryBuilder exclude() {
        return exclude;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("include", include.toMap());
        inner.put("exclude", exclude.toMap());
        if (dist != null) {
            inner.put("dist", dist);
        } else {
            if (pre != null) {
                inner.put("pre", pre);
            }
            if (post != null) {
                inner.put("post", post);
            }
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SpanNotQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && include.equals(other.include) && exclude.equals(other.exclude)
            && Objects.equals(pre, other.pre) && Objects.equals(post, other.post) && Objects.equals(dist, other.dist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), include, exclude, pre, post, dist);
    }
}
