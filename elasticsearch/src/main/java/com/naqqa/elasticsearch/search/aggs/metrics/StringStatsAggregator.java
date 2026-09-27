package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

public final class StringStatsAggregator extends Aggregator {

    private final SortedSetValues source;
    private final boolean showDistribution;
    private long[] counts = new long[4];
    private long[] minLengths = new long[4];
    private long[] maxLengths = new long[4];
    private double[] sumLengths = new double[4];
    private long[][] charFreqs = new long[4][];

    public StringStatsAggregator(String name, SortedSetValues source, boolean showDistribution) {
        super(name, new Aggregator[0]);
        this.source = source;
        this.showDistribution = showDistribution;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        boolean showDistribution = ParamsHelper.getBoolean(ctx.params(), "show_distribution", false);
        return new StringStatsAggregator(ctx.name(), ctx.lookup().bytesValues(field), showDistribution);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int minSize = (int) bucketOrd + 1;
        int oldLen = minLengths.length;
        counts = BucketArrays.grow(counts, minSize);
        minLengths = BucketArrays.grow(minLengths, minSize);
        maxLengths = BucketArrays.grow(maxLengths, minSize);
        sumLengths = BucketArrays.grow(sumLengths, minSize);
        charFreqs = BucketArrays.grow(charFreqs, minSize);
        if (minLengths.length > oldLen) {
            java.util.Arrays.fill(minLengths, oldLen, minLengths.length, Long.MAX_VALUE);
        }
        int b = (int) bucketOrd;
        if (charFreqs[b] == null) {
            charFreqs[b] = new long[256];
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            BytesRef ref = source.lookupOrd(source.nextOrd());
            int len = ref.length;
            counts[b]++;
            sumLengths[b] += len;
            if (len < minLengths[b]) {
                minLengths[b] = len;
            }
            if (len > maxLengths[b]) {
                maxLengths[b] = len;
            }
            for (int k = 0; k < len; k++) {
                charFreqs[b][ref.bytes[ref.offset + k] & 0xFF]++;
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        if (bucketOrd < 0 || b >= counts.length || counts[b] == 0) {
            return new InternalStringStats(name, 0, 0, 0, 0, new long[256], showDistribution, null);
        }
        return new InternalStringStats(name, counts[b], minLengths[b], maxLengths[b], sumLengths[b], charFreqs[b], showDistribution, null);
    }
}
