package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PercolateQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "percolate";

    private final String field;
    private List<Map<String, Object>> documents;
    private String index;
    private String id;
    private String routing;
    private String preference;
    private String version;

    public PercolateQueryBuilder(String field) {
        this.field = Objects.requireNonNull(field);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("field", "document", "documents", "index", "id", "routing",
        "preference", "version", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static PercolateQueryBuilder fromMap(Map<String, Object> value) {
        Object field = value.remove("field");
        if (field == null) {
            throw QueryParseUtils.error("[{}] requires a [field]", NAME);
        }
        PercolateQueryBuilder builder = new PercolateQueryBuilder(QueryParseUtils.asString(field));
        Object document = value.remove("document");
        Object documents = value.remove("documents");
        if (document != null) {
            builder.documents = new ArrayList<>();
            builder.documents.add(QueryParseUtils.asMap(document, NAME));
        } else if (documents != null) {
            builder.documents = new ArrayList<>();
            for (Object o : QueryParseUtils.asList(documents, NAME)) {
                builder.documents.add(QueryParseUtils.asMap(o, NAME));
            }
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "index" -> builder.index = QueryParseUtils.asString(e.getValue());
                case "id" -> builder.id = QueryParseUtils.asString(e.getValue());
                case "routing" -> builder.routing = QueryParseUtils.asString(e.getValue());
                case "preference" -> builder.preference = QueryParseUtils.asString(e.getValue());
                case "version" -> builder.version = QueryParseUtils.asString(e.getValue());
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
        }
        if (builder.documents == null && builder.id == null) {
            throw QueryParseUtils.error("[{}] requires one of [document], [documents] or [index]/[id]", NAME);
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String field() {
        return field;
    }

    public List<Map<String, Object>> documents() {
        return documents;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("field", field);
        if (documents != null) {
            if (documents.size() == 1) {
                inner.put("document", documents.get(0));
            } else {
                inner.put("documents", documents);
            }
        }
        if (index != null) {
            inner.put("index", index);
        }
        if (id != null) {
            inner.put("id", id);
        }
        if (routing != null) {
            inner.put("routing", routing);
        }
        if (preference != null) {
            inner.put("preference", preference);
        }
        if (version != null) {
            inner.put("version", version);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PercolateQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && field.equals(other.field) && Objects.equals(documents, other.documents)
            && Objects.equals(index, other.index) && Objects.equals(id, other.id) && Objects.equals(routing, other.routing)
            && Objects.equals(preference, other.preference) && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), field, documents, index, id, routing, preference, version);
    }
}
