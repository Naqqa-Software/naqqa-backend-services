package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalSingleBucketAggregation extends InternalAggregation {

    private final String type;
    private final long docCount;
    private final InternalAggregations aggregations;

    public InternalSingleBucketAggregation(String name, String type, long docCount, InternalAggregations aggregations, Map<String, Object> metadata) {
        super(name, metadata);
        this.type = type;
        this.docCount = docCount;
        this.aggregations = aggregations;
    }

    public long docCount() {
        return docCount;
    }

    public InternalAggregations aggregations() {
        return aggregations;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        long docCountAcc = 0;
        List<InternalAggregations> subs = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            InternalSingleBucketAggregation s = (InternalSingleBucketAggregation) a;
            docCountAcc += s.docCount;
            subs.add(s.aggregations);
        }
        InternalAggregations merged = InternalAggregations.reduceAll(subs, context);
        return new InternalSingleBucketAggregation(getName(), type, docCountAcc, merged, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("doc_count", docCount);
        map.putAll(aggregations.toMap());
        return map;
    }
}
