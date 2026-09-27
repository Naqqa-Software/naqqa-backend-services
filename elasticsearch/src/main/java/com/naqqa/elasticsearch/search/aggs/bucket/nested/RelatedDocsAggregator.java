package com.naqqa.elasticsearch.search.aggs.bucket.nested;

import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;

import java.util.function.IntFunction;

public final class RelatedDocsAggregator extends BucketsAggregator {

    private final String type;
    private final IntFunction<int[]> mapping;

    public RelatedDocsAggregator(String name, String type, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, IntFunction<int[]> mapping) {
        super(name, subAggregators, bucketConsumer);
        this.type = type;
        this.mapping = mapping;
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        int[] related = mapping.apply(doc);
        if (related == null) {
            return;
        }
        for (int relatedDoc : related) {
            collectBucket(relatedDoc, bucketOrd, 0L);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        long bucketOrd = bucketOrds.find(owningBucketOrd, 0L);
        long docCount = bucketDocCount(bucketOrd);
        InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
        return new InternalSingleBucketAggregation(name, type, docCount, subAggs, null);
    }
}
