package com.naqqa.elasticsearch.search.aggs;

import java.util.List;

public interface MultiBucketsAggregation {

    interface Bucket {

        Object getKey();

        String getKeyAsString();

        long getDocCount();

        InternalAggregations getAggregations();

        Bucket withAggregations(InternalAggregations aggregations);
    }

    List<? extends Bucket> getBuckets();

    InternalAggregation withBuckets(List<? extends Bucket> buckets);
}
