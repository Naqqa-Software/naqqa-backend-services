package com.naqqa.elasticsearch.search.aggs.pipeline;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalCardinality;
import com.naqqa.elasticsearch.search.aggs.support.sketch.HyperLogLogPlusPlus;

import java.util.ArrayList;
import java.util.List;

public final class CumulativeCardinalityAggregator {

    private CumulativeCardinalityAggregator() {
    }

    public static InternalAggregation apply(MultiBucketsAggregation parent, String subAggName, String outputName) {
        List<? extends MultiBucketsAggregation.Bucket> buckets = parent.getBuckets();
        List<MultiBucketsAggregation.Bucket> updated = new ArrayList<>(buckets.size());
        HyperLogLogPlusPlus running = null;
        for (MultiBucketsAggregation.Bucket b : buckets) {
            InternalAggregation agg = b.getAggregations().get(subAggName);
            if (agg instanceof InternalCardinality card) {
                if (running == null) {
                    running = new HyperLogLogPlusPlus(card.counts().precision(), 1);
                }
                running.merge(0, card.counts(), 0);
                updated.add(PipelineUtil.withExtraMetric(b, new InternalSimpleValue(outputName, running.cardinality(0), null)));
            } else {
                updated.add(b);
            }
        }
        return parent.withBuckets(updated);
    }
}
