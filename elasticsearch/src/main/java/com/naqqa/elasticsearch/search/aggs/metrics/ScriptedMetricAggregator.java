package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ScriptedMetricAggregator extends Aggregator {

    private final Supplier<Object> init;
    private final BiConsumer<Object, Integer> map;
    private final Function<Object, Object> combine;
    private final Function<List<Object>, Object> reduce;
    private Object[] states = new Object[4];

    public ScriptedMetricAggregator(String name, Supplier<Object> init, BiConsumer<Object, Integer> map,
                                     Function<Object, Object> combine, Function<List<Object>, Object> reduce) {
        super(name, new Aggregator[0]);
        this.init = init;
        this.map = map;
        this.combine = combine;
        this.reduce = reduce;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        Supplier<Object> init = (Supplier<Object>) ctx.params().get("init");
        BiConsumer<Object, Integer> map = (BiConsumer<Object, Integer>) ctx.params().get("map");
        Function<Object, Object> combine = (Function<Object, Object>) ctx.params().get("combine");
        Function<List<Object>, Object> reduce = (Function<List<Object>, Object>) ctx.params().get("reduce");
        return new ScriptedMetricAggregator(ctx.name(), init, map, combine, reduce);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        states = BucketArrays.grow(states, (int) bucketOrd + 1);
        int b = (int) bucketOrd;
        if (states[b] == null) {
            states[b] = init != null ? init.get() : new java.util.HashMap<>();
        }
        if (map != null) {
            map.accept(states[b], doc);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long bucketOrd) {
        int b = (int) bucketOrd;
        Object state = (bucketOrd >= 0 && b < states.length) ? states[b] : null;
        if (state == null) {
            state = init != null ? init.get() : new java.util.HashMap<>();
        }
        Object combined = combine != null ? combine.apply(state) : state;
        return new InternalScriptedMetric(name, java.util.Collections.singletonList(combined), reduce, null);
    }
}
