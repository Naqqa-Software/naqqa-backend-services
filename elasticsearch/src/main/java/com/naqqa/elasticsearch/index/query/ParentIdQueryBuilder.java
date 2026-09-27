package com.naqqa.elasticsearch.index.query;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ParentIdQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "parent_id";

    private final String type;
    private final String id;
    private boolean ignoreUnmapped = false;

    public ParentIdQueryBuilder(String type, String id) {
        this.type = Objects.requireNonNull(type);
        this.id = Objects.requireNonNull(id);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("type", "id", "ignore_unmapped", "boost", "_name");

    public static ParentIdQueryBuilder fromMap(Map<String, Object> value) {
        Object type = value.remove("type");
        Object id = value.remove("id");
        if (type == null || id == null) {
            throw QueryParseUtils.error("[{}] requires both [type] and [id]", NAME);
        }
        ParentIdQueryBuilder builder = new ParentIdQueryBuilder(QueryParseUtils.asString(type), QueryParseUtils.asString(id));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "ignore_unmapped" -> builder.ignoreUnmapped = QueryParseUtils.asBoolean(e.getValue());
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

    public String type() {
        return type;
    }

    public String id() {
        return id;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("type", type);
        inner.put("id", id);
        if (ignoreUnmapped) {
            inner.put("ignore_unmapped", true);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ParentIdQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && type.equals(other.type) && id.equals(other.id) && ignoreUnmapped == other.ignoreUnmapped;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), type, id, ignoreUnmapped);
    }
}
