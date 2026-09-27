package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

public final class TopMetricsAggregator extends Aggregator {

    private final int size;
    private final boolean ascending;
    private final ToDoubleFunction<Integer> sortValueExtractor;
    private final Map<String, ToDoubleFunction<Integer>> metrics;
    private DocQueue[] queues = new DocQueue[4];

    public TopMetricsAggregator(String name, int size, boolean ascending, ToDoubleFunction<Integer> sortValueExtractor, Map<String, ToDoubleFunction<Integer>> metrics) {
        super(name, new Aggregator[0]);
        this.size = size;
        this.ascending = ascending;
        this.sortValueExtractor = sortValueExtractor;
        this.metrics = metrics;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        int size = ParamsHelper.getInt(ctx.params(), "size", 1);
        boolean ascending = ParamsHelper.getBoolean(ctx.params(), "ascending", true);
        ToDoubleFunction<Integer> sortValueExtractor = (ToDoubleFunction<Integer>) ctx.params().get("sort_value");
        Map<String, ToDoubleFunction<Integer>> metrics = (Map<String, ToDoubleFunction<Integer>>) ctx.params().get("metrics");
        return new TopMetricsAggregator(ctx.name(), size, ascending, sortValueExtractor, metrics);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        queues = BucketArrays.grow(queues, (int) bucketOrd + 1);
        int b = (int) bucketOrd;
        if (queues[b] == null) {
            queues[b] = new DocQueue(size, ascending, sortValueExtractor);
        }
        queues[b].insertWithOverflow(doc);
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        List<InternalTopMetrics.TopMetric> top = new ArrayList<>();
        if (bucketOrd >= 0 && b < queues.length && queues[b] != null) {
            Integer[] docs = queues[b].drainToArrayHighestFirst(new Integer[0]);
            for (Integer doc : docs) {
                if (doc == null) {
                    continue;
                }
                LinkedHashMap<String, Double> values = new LinkedHashMap<>();
                if (metrics != null) {
                    for (Map.Entry<String, ToDoubleFunction<Integer>> e : metrics.entrySet()) {
                        values.put(e.getKey(), e.getValue().applyAsDouble(doc));
                    }
                }
                top.add(new InternalTopMetrics.TopMetric(sortValueExtractor.applyAsDouble(doc), values));
            }
        }
        return new InternalTopMetrics(name, size, ascending, top, null);
    }

    private static final class DocQueue extends PriorityQueue<Integer> {
        private final boolean ascending;
        private final ToDoubleFunction<Integer> sortValueExtractor;

        DocQueue(int maxSize, boolean ascending, ToDoubleFunction<Integer> sortValueExtractor) {
            super(Math.max(maxSize, 1), false);
            this.ascending = ascending;
            this.sortValueExtractor = sortValueExtractor;
        }

        @Override
        protected boolean lessThan(Integer a, Integer b) {
            int cmp = Double.compare(sortValueExtractor.applyAsDouble(a), sortValueExtractor.applyAsDouble(b));
            return ascending ? cmp > 0 : cmp < 0;
        }
    }
}
