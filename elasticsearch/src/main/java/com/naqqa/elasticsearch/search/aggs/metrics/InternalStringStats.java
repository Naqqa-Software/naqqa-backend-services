package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalStringStats extends InternalAggregation {

    private final long count;
    private final long minLength;
    private final long maxLength;
    private final double sumLength;
    private final long[] charFreq;
    private final boolean showDistribution;

    public InternalStringStats(String name, long count, long minLength, long maxLength, double sumLength, long[] charFreq, boolean showDistribution, Map<String, Object> metadata) {
        super(name, metadata);
        this.count = count;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.sumLength = sumLength;
        this.charFreq = charFreq;
        this.showDistribution = showDistribution;
    }

    public double entropy() {
        long total = 0;
        for (long f : charFreq) {
            total += f;
        }
        if (total == 0) {
            return 0;
        }
        double entropy = 0;
        for (long f : charFreq) {
            if (f > 0) {
                double p = (double) f / total;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    @Override
    public String getType() {
        return "string_stats";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        long countAcc = 0;
        long minAcc = Long.MAX_VALUE;
        long maxAcc = Long.MIN_VALUE;
        double sumAcc = 0;
        long[] freqAcc = new long[256];
        for (InternalAggregation a : aggregations) {
            InternalStringStats s = (InternalStringStats) a;
            countAcc += s.count;
            if (s.count > 0) {
                minAcc = Math.min(minAcc, s.minLength);
                maxAcc = Math.max(maxAcc, s.maxLength);
            }
            sumAcc += s.sumLength;
            for (int i = 0; i < 256; i++) {
                freqAcc[i] += s.charFreq[i];
            }
        }
        if (countAcc == 0) {
            minAcc = 0;
            maxAcc = 0;
        }
        return new InternalStringStats(getName(), countAcc, minAcc, maxAcc, sumAcc, freqAcc, showDistribution, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("count", count);
        map.put("min_length", count == 0 ? null : minLength);
        map.put("max_length", count == 0 ? null : maxLength);
        map.put("avg_length", count == 0 ? null : sumLength / count);
        map.put("entropy", entropy());
        if (showDistribution) {
            LinkedHashMap<String, Object> dist = new LinkedHashMap<>();
            long total = 0;
            for (long f : charFreq) {
                total += f;
            }
            if (total > 0) {
                for (int i = 0; i < 256; i++) {
                    if (charFreq[i] > 0) {
                        dist.put(String.valueOf((char) i), (double) charFreq[i] / total);
                    }
                }
            }
            map.put("distribution", dist);
        }
        return map;
    }
}
