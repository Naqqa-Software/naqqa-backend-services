package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PinnedQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "pinned";

    private final List<String> ids = new ArrayList<>();
    private final List<Map<String, Object>> docs = new ArrayList<>();
    private final QueryBuilder organic;

    public PinnedQueryBuilder(QueryBuilder organic) {
        this.organic = Objects.requireNonNull(organic);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("ids", "docs", "organic", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static PinnedQueryBuilder fromMap(Map<String, Object> value) {
        Object organic = value.remove("organic");
        if (organic == null) {
            throw QueryParseUtils.error("[{}] requires an [organic] query", NAME);
        }
        PinnedQueryBuilder builder = new PinnedQueryBuilder(QueryParser.parseQuery((Map<String, Object>) organic));
        Object ids = value.remove("ids");
        if (ids != null) {
            builder.ids.addAll(QueryParseUtils.asStringList(ids));
        }
        Object docs = value.remove("docs");
        if (docs != null) {
            for (Object o : QueryParseUtils.asList(docs, NAME)) {
                builder.docs.add(QueryParseUtils.asMap(o, NAME));
            }
        }
        if (builder.ids.isEmpty() && builder.docs.isEmpty()) {
            throw QueryParseUtils.error("[{}] requires one of [ids] or [docs]", NAME);
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

    public List<String> ids() {
        return ids;
    }

    public QueryBuilder organic() {
        return organic;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        if (!ids.isEmpty()) {
            inner.put("ids", ids);
        }
        if (!docs.isEmpty()) {
            inner.put("docs", docs);
        }
        inner.put("organic", organic.toMap());
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PinnedQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && ids.equals(other.ids) && docs.equals(other.docs) && organic.equals(other.organic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), ids, docs, organic);
    }
}
