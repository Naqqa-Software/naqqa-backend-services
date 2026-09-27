package com.naqqa.elasticsearch.search.aggs.bucket.range;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class IpRangeBucket implements MultiBucketsAggregation.Bucket {

    private final IpRangeSpec spec;
    private final long docCount;
    private final InternalAggregations aggregations;

    public IpRangeBucket(IpRangeSpec spec, long docCount, InternalAggregations aggregations) {
        this.spec = spec;
        this.docCount = docCount;
        this.aggregations = aggregations;
    }

    public IpRangeSpec spec() {
        return spec;
    }

    @Override
    public Object getKey() {
        return spec.effectiveKey();
    }

    @Override
    public String getKeyAsString() {
        return spec.effectiveKey();
    }

    @Override
    public long getDocCount() {
        return docCount;
    }

    @Override
    public InternalAggregations getAggregations() {
        return aggregations;
    }

    @Override
    public IpRangeBucket withAggregations(InternalAggregations aggregations) {
        return new IpRangeBucket(spec, docCount, aggregations);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", spec.effectiveKey());
        map.put("doc_count", docCount);
        map.putAll(aggregations.toMap());
        return map;
    }
}
