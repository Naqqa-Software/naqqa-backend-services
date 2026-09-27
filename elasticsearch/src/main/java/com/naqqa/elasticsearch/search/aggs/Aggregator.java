package com.naqqa.elasticsearch.search.aggs;

public abstract class Aggregator {

    protected final String name;
    protected final Aggregator[] subAggregators;

    protected Aggregator(String name, Aggregator[] subAggregators) {
        this.name = name;
        this.subAggregators = subAggregators == null ? new Aggregator[0] : subAggregators;
    }

    public final String name() {
        return name;
    }

    public final Aggregator[] subAggregators() {
        return subAggregators;
    }

    public abstract void collect(int doc, long bucketOrd);

    public abstract InternalAggregation buildAggregation(long bucketOrd);

    public void postCollection() {
        for (Aggregator sub : subAggregators) {
            sub.postCollection();
        }
    }
}
