package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.sketch.DoubleHdrHistogram;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalPercentileRanks extends InternalAggregation {

    private final PercentilesMethod method;
    private final double[] values;
    private final TDigest digest;
    private final DoubleHdrHistogram histogram;

    public InternalPercentileRanks(String name, PercentilesMethod method, double[] values, TDigest digest, DoubleHdrHistogram histogram, Map<String, Object> metadata) {
        super(name, metadata);
        this.method = method;
        this.values = values;
        this.digest = digest;
        this.histogram = histogram;
    }

    public double rank(double value) {
        if (method == PercentilesMethod.TDIGEST) {
            return digest.totalWeight() == 0 ? Double.NaN : digest.cdf(value) * 100.0;
        }
        return histogram.getTotalCount() == 0 ? Double.NaN : histogram.getPercentileAtOrBelowValue(value);
    }

    @Override
    public String getType() {
        return "percentile_ranks";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        if (method == PercentilesMethod.TDIGEST) {
            TDigest merged = new TDigest(digest.compression());
            for (InternalAggregation a : aggregations) {
                merged.add(((InternalPercentileRanks) a).digest);
            }
            return new InternalPercentileRanks(getName(), method, values, merged, null, getMetadata());
        }
        DoubleHdrHistogram merged = null;
        for (InternalAggregation a : aggregations) {
            DoubleHdrHistogram other = ((InternalPercentileRanks) a).histogram;
            if (merged == null) {
                merged = other.copy();
            } else {
                merged.add(other);
            }
        }
        return new InternalPercentileRanks(getName(), method, values, null, merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> ranks = new LinkedHashMap<>();
        for (double v : values) {
            double r = rank(v);
            ranks.put(String.valueOf(v), Double.isNaN(r) ? null : r);
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("values", ranks);
        return map;
    }
}
