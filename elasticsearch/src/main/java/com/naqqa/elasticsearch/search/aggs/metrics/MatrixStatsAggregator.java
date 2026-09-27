package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.List;

public final class MatrixStatsAggregator extends Aggregator {

    private final String[] fields;
    private final DoubleValuesSource[] sources;
    private MatrixStatsState[] states = new MatrixStatsState[4];

    public MatrixStatsAggregator(String name, String[] fields, DoubleValuesSource[] sources) {
        super(name, new Aggregator[0]);
        this.fields = fields;
        this.sources = sources;
    }

    public static Aggregator parse(AggParseContext ctx) {
        List<Object> fieldList = ParamsHelper.asList(ctx.params().get("fields"));
        String[] fields = new String[fieldList.size()];
        DoubleValuesSource[] sources = new DoubleValuesSource[fieldList.size()];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = String.valueOf(fieldList.get(i));
            sources[i] = ctx.lookup().doubleValues(fields[i]);
        }
        return new MatrixStatsAggregator(ctx.name(), fields, sources);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        double[] values = new double[fields.length];
        for (int i = 0; i < sources.length; i++) {
            if (!sources[i].advanceExact(doc) || sources[i].docValueCount() == 0) {
                return;
            }
            values[i] = sources[i].nextValue();
        }
        states = BucketArrays.grow(states, (int) bucketOrd + 1);
        int b = (int) bucketOrd;
        if (states[b] == null) {
            states[b] = new MatrixStatsState(fields);
        }
        states[b].add(values);
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        MatrixStatsState state = (bucketOrd >= 0 && b < states.length && states[b] != null) ? states[b] : new MatrixStatsState(fields);
        return new InternalMatrixStats(name, state, null);
    }
}
