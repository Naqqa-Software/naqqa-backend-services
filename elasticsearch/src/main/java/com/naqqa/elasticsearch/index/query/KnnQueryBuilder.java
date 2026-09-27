package com.naqqa.elasticsearch.index.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class KnnQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "knn";

    private final String field;
    private final List<Float> queryVector;
    private final int k;
    private Integer numCandidates;
    private QueryBuilder filter;
    private Float similarity;

    public KnnQueryBuilder(String field, List<Float> queryVector, int k) {
        this.field = Objects.requireNonNull(field);
        this.queryVector = new ArrayList<>(Objects.requireNonNull(queryVector));
        this.k = k;
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("field", "query_vector", "k", "num_candidates", "filter", "similarity", "boost", "_name");

    @SuppressWarnings("unchecked")
    public static KnnQueryBuilder fromMap(Map<String, Object> value) {
        Object field = value.remove("field");
        Object queryVector = value.remove("query_vector");
        Object k = value.remove("k");
        if (field == null || queryVector == null || k == null) {
            throw QueryParseUtils.error("[{}] requires [field], [query_vector] and [k]", NAME);
        }
        List<Float> vector = new ArrayList<>();
        for (Object o : QueryParseUtils.asList(queryVector, NAME)) {
            vector.add(QueryParseUtils.asFloat(o));
        }
        KnnQueryBuilder builder = new KnnQueryBuilder(QueryParseUtils.asString(field), vector, QueryParseUtils.asInt(k));
        Object filter = value.remove("filter");
        if (filter != null) {
            builder.filter = QueryParser.parseQuery((Map<String, Object>) filter);
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "num_candidates" -> builder.numCandidates = QueryParseUtils.asInt(e.getValue());
                case "similarity" -> builder.similarity = QueryParseUtils.asFloat(e.getValue());
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

    public String field() {
        return field;
    }

    public List<Float> queryVector() {
        return queryVector;
    }

    public int k() {
        return k;
    }

    public QueryBuilder filter() {
        return filter;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("field", field);
        inner.put("query_vector", queryVector);
        inner.put("k", k);
        if (numCandidates != null) {
            inner.put("num_candidates", numCandidates);
        }
        if (filter != null) {
            inner.put("filter", filter.toMap());
        }
        if (similarity != null) {
            inner.put("similarity", similarity);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof KnnQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && field.equals(other.field) && queryVector.equals(other.queryVector) && k == other.k
            && Objects.equals(numCandidates, other.numCandidates) && Objects.equals(filter, other.filter) && Objects.equals(similarity, other.similarity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), field, queryVector, k, numCandidates, filter, similarity);
    }
}
