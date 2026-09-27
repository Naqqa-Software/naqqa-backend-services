package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VariableWidthHistogramAggregator extends BucketsAggregator {

    private final DoubleValuesSource source;
    private final int targetBuckets;
    private final int initialBuckets;
    private long[] owning = new long[16];
    private int[] docs = new int[16];
    private double[] values = new double[16];
    private int bufferedCount = 0;
    private final Set<Long> finalizedOwningOrds = new HashSet<>();
    private final Map<Long, double[]> clusterBounds = new HashMap<>();

    public VariableWidthHistogramAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer,
                                             DoubleValuesSource source, int targetBuckets, int initialBuckets) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.targetBuckets = targetBuckets;
        this.initialBuckets = initialBuckets;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        int buckets = ParamsHelper.getInt(ctx.params(), "buckets", 10);
        int initialBuckets = ParamsHelper.getInt(ctx.params(), "initial_buckets", Math.max(buckets * 3, buckets));
        return new VariableWidthHistogramAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().doubleValues(field), buckets, initialBuckets);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            owning = BucketArrays.grow(owning, bufferedCount + 1);
            docs = BucketArrays.grow(docs, bufferedCount + 1);
            values = BucketArrays.grow(values, bufferedCount + 1);
            owning[bufferedCount] = bucketOrd;
            docs[bufferedCount] = doc;
            values[bufferedCount] = source.nextValue();
            bufferedCount++;
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        if (!finalizedOwningOrds.contains(owningBucketOrd)) {
            finalizedOwningOrds.add(owningBucketOrd);
            finalize(owningBucketOrd);
        }
        List<VariableWidthHistogramBucket> buckets = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            double[] bounds = clusterBounds.getOrDefault(bucketOrd, new double[]{0, 0, 0});
            long docCount = bucketDocCount(bucketOrd);
            double key = docCount == 0 ? bounds[0] : bounds[2] / docCount;
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new VariableWidthHistogramBucket(key, bounds[0], bounds[1], docCount, subAggs));
        }
        buckets.sort((a, b) -> Double.compare((double) a.getKey(), (double) b.getKey()));
        return new InternalVariableWidthHistogram(name, buckets, targetBuckets, null);
    }

    private void finalize(long owningBucketOrd) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < bufferedCount; i++) {
            if (owning[i] == owningBucketOrd) {
                indices.add(i);
            }
        }
        if (indices.isEmpty()) {
            return;
        }
        indices.sort((a, b) -> Double.compare(values[a], values[b]));
        int n = indices.size();
        int groups = Math.max(1, Math.min(initialBuckets, n));
        List<int[]> ranges = new ArrayList<>();
        int base = n / groups;
        int rem = n % groups;
        int pos = 0;
        for (int g = 0; g < groups; g++) {
            int size = base + (g < rem ? 1 : 0);
            ranges.add(new int[]{pos, pos + size});
            pos += size;
        }
        List<double[]> stats = new ArrayList<>();
        for (int[] r : ranges) {
            double sum = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int k = r[0]; k < r[1]; k++) {
                double v = values[indices.get(k)];
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            stats.add(new double[]{min, max, sum, r[1] - r[0]});
        }
        while (ranges.size() > targetBuckets && ranges.size() > 1) {
            int bestIdx = 0;
            double bestGap = Double.POSITIVE_INFINITY;
            for (int i = 0; i < ranges.size() - 1; i++) {
                double centroidA = stats.get(i)[3] == 0 ? 0 : stats.get(i)[2] / stats.get(i)[3];
                double centroidB = stats.get(i + 1)[3] == 0 ? 0 : stats.get(i + 1)[2] / stats.get(i + 1)[3];
                double gap = centroidB - centroidA;
                if (gap < bestGap) {
                    bestGap = gap;
                    bestIdx = i;
                }
            }
            int[] a = ranges.get(bestIdx);
            int[] b = ranges.get(bestIdx + 1);
            double[] sa = stats.get(bestIdx);
            double[] sb = stats.get(bestIdx + 1);
            ranges.set(bestIdx, new int[]{a[0], b[1]});
            stats.set(bestIdx, new double[]{Math.min(sa[0], sb[0]), Math.max(sa[1], sb[1]), sa[2] + sb[2], sa[3] + sb[3]});
            ranges.remove(bestIdx + 1);
            stats.remove(bestIdx + 1);
        }
        for (int g = 0; g < ranges.size(); g++) {
            int[] r = ranges.get(g);
            long clusterId = g;
            Long globalBucketOrd = null;
            for (int k = r[0]; k < r[1]; k++) {
                int bufferedIdx = indices.get(k);
                long bucketOrd = collectBucket(docs[bufferedIdx], owningBucketOrd, clusterId);
                globalBucketOrd = bucketOrd;
            }
            if (globalBucketOrd != null) {
                clusterBounds.put(globalBucketOrd, stats.get(g));
            }
        }
    }
}
