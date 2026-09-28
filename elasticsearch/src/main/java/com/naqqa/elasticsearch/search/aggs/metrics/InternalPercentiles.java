package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.sketch.DoubleHdrHistogram;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalPercentiles extends InternalAggregation {

    private final PercentilesMethod method;
    private final double[] percents;
    private final TDigest digest;
    private final DoubleHdrHistogram histogram;

    public InternalPercentiles(String name, PercentilesMethod method, double[] percents, TDigest digest, DoubleHdrHistogram histogram, Map<String, Object> metadata) {
        super(name, metadata);
        this.method = method;
        this.percents = percents;
        this.digest = digest;
        this.histogram = histogram;
    }

    public PercentilesMethod methodValue() {
        return method;
    }

    public double[] percentsValue() {
        return percents;
    }

    public TDigest digestValue() {
        return digest;
    }

    public DoubleHdrHistogram histogramValue() {
        return histogram;
    }

    public double percentile(double p) {
        if (method == PercentilesMethod.TDIGEST) {
            return digest.totalWeight() == 0 ? Double.NaN : digest.quantile(p / 100.0);
        }
        return histogram.getTotalCount() == 0 ? Double.NaN : histogram.getValueAtPercentile(p);
    }

    @Override
    public String getType() {
        return "percentiles";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        if (method == PercentilesMethod.TDIGEST) {
            TDigest merged = new TDigest(digest.compression());
            for (InternalAggregation a : aggregations) {
                merged.add(((InternalPercentiles) a).digest);
            }
            return new InternalPercentiles(getName(), method, percents, merged, null, getMetadata());
        }
        DoubleHdrHistogram merged = histogram.copy();
        boolean first = true;
        for (InternalAggregation a : aggregations) {
            DoubleHdrHistogram other = ((InternalPercentiles) a).histogram;
            if (first) {
                merged = other.copy();
                first = false;
            } else {
                merged.add(other);
            }
        }
        return new InternalPercentiles(getName(), method, percents, null, merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (double p : percents) {
            double v = percentile(p);
            values.put(formatKey(p), Double.isNaN(v) ? null : v);
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("values", values);
        return map;
    }

    private static String formatKey(double p) {
        if (p == Math.floor(p)) {
            return String.valueOf((long) p);
        }
        return String.valueOf(p);
    }
}
