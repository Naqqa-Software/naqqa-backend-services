package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

public final class RateAggregator extends Aggregator {

    public enum Mode {
        SUM, VALUE_COUNT
    }

    private final DoubleValuesSource source;
    private final Mode mode;
    private final double divisor;
    private double[] sums = new double[4];

    public RateAggregator(String name, DoubleValuesSource source, Mode mode, double divisor) {
        super(name, new Aggregator[0]);
        this.source = source;
        this.mode = mode;
        this.divisor = divisor;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        Mode mode = "value_count".equals(ParamsHelper.getString(ctx.params(), "mode", "sum")) ? Mode.VALUE_COUNT : Mode.SUM;
        double divisor = ParamsHelper.getDouble(ctx.params(), "divisor", 1.0);
        return new RateAggregator(ctx.name(), ctx.lookup().doubleValues(field), mode, divisor);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        sums = BucketArrays.grow(sums, (int) bucketOrd + 1);
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double v = source.nextValue();
            sums[(int) bucketOrd] += mode == Mode.VALUE_COUNT ? 1 : v;
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        double v = bucketOrd >= 0 && bucketOrd < sums.length ? sums[(int) bucketOrd] : 0;
        return new InternalRate(name, v, divisor, null);
    }
}
