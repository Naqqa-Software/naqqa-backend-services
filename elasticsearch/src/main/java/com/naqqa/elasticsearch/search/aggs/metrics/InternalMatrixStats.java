package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalMatrixStats extends InternalAggregation {

    private final MatrixStatsState state;

    public InternalMatrixStats(String name, MatrixStatsState state, Map<String, Object> metadata) {
        super(name, metadata);
        this.state = state;
    }

    public MatrixStatsState state() {
        return state;
    }

    @Override
    public String getType() {
        return "matrix_stats";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        MatrixStatsState merged = new MatrixStatsState(state.fields);
        for (InternalAggregation a : aggregations) {
            merged = merged.merge(((InternalMatrixStats) a).state);
        }
        return new InternalMatrixStats(getName(), merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("doc_count", state.count);
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < state.fields.length; i++) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("count", state.count);
            entry.put("mean", state.means[i]);
            entry.put("variance", state.variance(i));
            LinkedHashMap<String, Object> cov = new LinkedHashMap<>();
            LinkedHashMap<String, Object> corr = new LinkedHashMap<>();
            for (int j = 0; j < state.fields.length; j++) {
                cov.put(state.fields[j], state.covariance(i, j));
                corr.put(state.fields[j], state.correlation(i, j));
            }
            entry.put("covariance", cov);
            entry.put("correlation", corr);
            fields.put(state.fields[i], entry);
        }
        map.put("fields", fields);
        return map;
    }
}
