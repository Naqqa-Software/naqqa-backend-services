package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

public final class FiltersAggregator extends BucketsAggregator {

    private final List<String> names;
    private final List<IntPredicate> predicates;
    private final boolean otherBucket;
    private final String otherBucketKey;
    private final boolean keyed;

    public FiltersAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, List<String> names,
                              List<IntPredicate> predicates, boolean otherBucket, String otherBucketKey, boolean keyed) {
        super(name, subAggregators, bucketConsumer);
        this.names = names;
        this.predicates = predicates;
        this.otherBucket = otherBucket;
        this.otherBucketKey = otherBucketKey;
        this.keyed = keyed;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        Map<String, Object> filters = ParamsHelper.asMap(ctx.params().get("filters"));
        List<String> names = new ArrayList<>();
        List<IntPredicate> predicates = new ArrayList<>();
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            names.add(e.getKey());
            predicates.add((IntPredicate) e.getValue());
        }
        boolean otherBucket = ParamsHelper.getBoolean(ctx.params(), "other_bucket", false);
        String otherBucketKey = ParamsHelper.getString(ctx.params(), "other_bucket_key", "_other_");
        boolean keyed = ParamsHelper.getBoolean(ctx.params(), "keyed", true);
        return new FiltersAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), names, predicates, otherBucket, otherBucketKey, keyed);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        boolean matchedAny = false;
        for (int i = 0; i < predicates.size(); i++) {
            if (predicates.get(i).test(doc)) {
                matchedAny = true;
                collectBucket(doc, bucketOrd, i);
            }
        }
        if (!matchedAny && otherBucket) {
            collectBucket(doc, bucketOrd, predicates.size());
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<FiltersBucket> buckets = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            long bucketOrd = bucketOrds.find(owningBucketOrd, i);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new FiltersBucket(names.get(i), bucketDocCount(bucketOrd), subAggs));
        }
        if (otherBucket) {
            long bucketOrd = bucketOrds.find(owningBucketOrd, predicates.size());
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new FiltersBucket(otherBucketKey, bucketDocCount(bucketOrd), subAggs));
        }
        return new InternalFilters(name, buckets, keyed, null);
    }
}
