package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;
import com.naqqa.elasticsearch.search.aggs.support.sketch.HyperLogLogPlusPlus;

public final class CardinalityAggregator extends Aggregator {

    private final DoubleValuesSource numericSource;
    private final SortedSetValues bytesSource;
    private final int precision;
    private final HyperLogLogPlusPlus counts;

    public CardinalityAggregator(String name, DoubleValuesSource numericSource, SortedSetValues bytesSource, int precision) {
        super(name, new Aggregator[0]);
        this.numericSource = numericSource;
        this.bytesSource = bytesSource;
        this.precision = precision;
        this.counts = new HyperLogLogPlusPlus(precision, 1);
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        String fieldType = ParamsHelper.getString(ctx.params(), "field_type", "keyword");
        long threshold = ParamsHelper.getLong(ctx.params(), "precision_threshold", HyperLogLogPlusPlus.thresholdFromPrecision(HyperLogLogPlusPlus.DEFAULT_PRECISION));
        int precision = HyperLogLogPlusPlus.precisionFromThreshold(threshold);
        if ("numeric".equals(fieldType)) {
            return new CardinalityAggregator(ctx.name(), ctx.lookup().doubleValues(field), null, precision);
        }
        return new CardinalityAggregator(ctx.name(), null, ctx.lookup().bytesValues(field), precision);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (numericSource != null) {
            if (!numericSource.advanceExact(doc)) {
                return;
            }
            int n = numericSource.docValueCount();
            for (int i = 0; i < n; i++) {
                counts.collectDouble(bucketOrd, numericSource.nextValue());
            }
        } else {
            if (!bytesSource.advanceExact(doc)) {
                return;
            }
            int n = bytesSource.docValueCount();
            for (int i = 0; i < n; i++) {
                BytesRef ref = bytesSource.lookupOrd(bytesSource.nextOrd());
                counts.collectBytes(bucketOrd, ref.toBytes());
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        HyperLogLogPlusPlus single = new HyperLogLogPlusPlus(precision, 1);
        if (bucketOrd >= 0 && bucketOrd < counts.maxOrd()) {
            single.merge(0, counts, bucketOrd);
        }
        return new InternalCardinality(name, single, null);
    }
}
