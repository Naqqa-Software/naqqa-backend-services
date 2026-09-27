package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ToDoubleFunction;

public final class TopHitsAggregator extends Aggregator {

    private final int size;
    private final Comparator<Integer> comparator;
    private final ToDoubleFunction<Integer> sortValueExtractor;
    private final boolean ascending;
    private final IntFunction<Map<String, Object>> sourceLookup;
    private DocQueue[] queues = new DocQueue[4];

    public TopHitsAggregator(String name, int size, Comparator<Integer> comparator, ToDoubleFunction<Integer> sortValueExtractor,
                              boolean ascending, IntFunction<Map<String, Object>> sourceLookup) {
        super(name, new Aggregator[0]);
        this.size = size;
        this.comparator = comparator;
        this.sortValueExtractor = sortValueExtractor;
        this.ascending = ascending;
        this.sourceLookup = sourceLookup;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        int size = ParamsHelper.getInt(ctx.params(), "size", 3);
        Comparator<Integer> comparator = (Comparator<Integer>) ctx.params().get("comparator");
        ToDoubleFunction<Integer> sortValueExtractor = (ToDoubleFunction<Integer>) ctx.params().getOrDefault("sort_value", (ToDoubleFunction<Integer>) Integer::doubleValue);
        boolean ascending = ParamsHelper.getBoolean(ctx.params(), "ascending", true);
        IntFunction<Map<String, Object>> sourceLookup = (IntFunction<Map<String, Object>>) ctx.params().get("source_lookup");
        if (comparator == null) {
            IntUnaryOperator sign = ascending ? (i -> i) : (i -> -i);
            comparator = Comparator.comparingDouble(sortValueExtractor::applyAsDouble);
            if (!ascending) {
                comparator = comparator.reversed();
            }
        }
        return new TopHitsAggregator(ctx.name(), size, comparator, sortValueExtractor, ascending, sourceLookup);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        queues = BucketArrays.grow(queues, (int) bucketOrd + 1);
        int b = (int) bucketOrd;
        if (queues[b] == null) {
            queues[b] = new DocQueue(size, comparator);
        }
        queues[b].insertWithOverflow(doc);
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        List<InternalTopHits.Hit> hits = new ArrayList<>();
        if (bucketOrd >= 0 && b < queues.length && queues[b] != null) {
            Integer[] docs = queues[b].drainToArrayHighestFirst(new Integer[0]);
            for (Integer doc : docs) {
                if (doc != null) {
                    Map<String, Object> source = sourceLookup != null ? sourceLookup.apply(doc) : Map.of();
                    hits.add(new InternalTopHits.Hit(sortValueExtractor.applyAsDouble(doc), source));
                }
            }
        }
        return new InternalTopHits(name, size, ascending, hits, null);
    }

    private static final class DocQueue extends PriorityQueue<Integer> {
        private final Comparator<Integer> comparator;

        DocQueue(int maxSize, Comparator<Integer> comparator) {
            super(Math.max(maxSize, 1), false);
            this.comparator = comparator;
        }

        @Override
        protected boolean lessThan(Integer a, Integer b) {
            return comparator.compare(a, b) > 0;
        }
    }
}
