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

public final class PercentileRanksAggregator extends Aggregator {

    private final DoubleValuesSource source;
    private final PercentilesMethod method;
    private final double[] values;
    private final double compression;
    private final int significantDigits;
    private TDigest[] digests;
    private DoubleHdrHistogram[] histograms;

    public PercentileRanksAggregator(String name, DoubleValuesSource source, PercentilesMethod method, double[] values, double compression, int significantDigits) {
        super(name, new Aggregator[0]);
        this.source = source;
        this.method = method;
        this.values = values;
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
        List<Object> valuesList = ParamsHelper.asList(ctx.params().get("values"));
        double[] values = new double[valuesList.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = ((Number) valuesList.get(i)).doubleValue();
        }
        double compression = ParamsHelper.getDouble(ctx.params(), "compression", TDigest.DEFAULT_COMPRESSION);
        int digits = ParamsHelper.getInt(ctx.params(), "number_of_significant_value_digits", 3);
        return new PercentileRanksAggregator(ctx.name(), ctx.lookup().doubleValues(field), method, values, compression, digits);
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
            return new InternalPercentileRanks(name, method, values, d, null, null);
        }
        DoubleHdrHistogram h = (bucketOrd >= 0 && b < histograms.length && histograms[b] != null) ? histograms[b] : new DoubleHdrHistogram(significantDigits);
        return new InternalPercentileRanks(name, method, values, null, h, null);
    }
}
