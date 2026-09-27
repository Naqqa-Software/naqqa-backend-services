package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.sketch.DoubleHdrHistogram;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

import java.util.List;

public final class PercentilesAggregator extends Aggregator {

    private static final double[] DEFAULT_PERCENTS = {1, 5, 25, 50, 75, 95, 99};

    private final DoubleValuesSource source;
    private final PercentilesMethod method;
    private final double[] percents;
    private final double compression;
    private final int significantDigits;
    private TDigest[] digests;
    private DoubleHdrHistogram[] histograms;

    public PercentilesAggregator(String name, DoubleValuesSource source, PercentilesMethod method, double[] percents, double compression, int significantDigits) {
        super(name, new Aggregator[0]);
        this.source = source;
        this.method = method;
        this.percents = percents;
        this.compression = compression;
        this.significantDigits = significantDigits;
        if (method == PercentilesMethod.TDIGEST) {
            this.digests = new TDigest[4];
        } else {
            this.histograms = new DoubleHdrHistogram[4];
        }
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        PercentilesMethod method = PercentilesMethod.fromString(ParamsHelper.getString(ctx.params(), "method", "tdigest"));
        List<Object> percentsList = ParamsHelper.asList(ctx.params().get("percents"));
        double[] percents = DEFAULT_PERCENTS;
        if (!percentsList.isEmpty()) {
            percents = new double[percentsList.size()];
            for (int i = 0; i < percents.length; i++) {
                percents[i] = ((Number) percentsList.get(i)).doubleValue();
            }
        }
        double compression = ParamsHelper.getDouble(ctx.params(), "compression", TDigest.DEFAULT_COMPRESSION);
        int digits = ParamsHelper.getInt(ctx.params(), "number_of_significant_value_digits", 3);
        return new PercentilesAggregator(ctx.name(), ctx.lookup().doubleValues(field), method, percents, compression, digits);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        int b = (int) bucketOrd;
        if (method == PercentilesMethod.TDIGEST) {
            digests = BucketArrays.grow(digests, b + 1);
            if (digests[b] == null) {
                digests[b] = new TDigest(compression);
            }
            for (int i = 0; i < n; i++) {
                digests[b].add(source.nextValue());
            }
        } else {
            histograms = BucketArrays.grow(histograms, b + 1);
            if (histograms[b] == null) {
                histograms[b] = new DoubleHdrHistogram(significantDigits);
            }
            for (int i = 0; i < n; i++) {
                histograms[b].recordValue(Math.max(0, source.nextValue()));
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        if (method == PercentilesMethod.TDIGEST) {
            TDigest d = (bucketOrd >= 0 && b < digests.length && digests[b] != null) ? digests[b] : new TDigest(compression);
            return new InternalPercentiles(name, method, percents, d, null, null);
        }
        DoubleHdrHistogram h = (bucketOrd >= 0 && b < histograms.length && histograms[b] != null) ? histograms[b] : new DoubleHdrHistogram(significantDigits);
        return new InternalPercentiles(name, method, percents, null, h, null);
    }
}
