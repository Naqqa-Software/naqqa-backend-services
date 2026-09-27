package com.naqqa.elasticsearch.search.aggs.bucket.histogram;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DateHistogramBucket implements MultiBucketsAggregation.Bucket {

    private final long key;
    private final long docCount;
    private final InternalAggregations aggregations;

    public DateHistogramBucket(long key, long docCount, InternalAggregations aggregations) {
        this.key = key;
        this.docCount = docCount;
        this.aggregations = aggregations;
    }

    @Override
    public Long getKey() {
        return key;
    }

    @Override
    public String getKeyAsString() {
        return DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(key));
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
    public DateHistogramBucket withAggregations(InternalAggregations aggregations) {
        return new DateHistogramBucket(key, docCount, aggregations);
    }

    public Map<String, Object> toMap(ZoneId zone) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("key_as_string", java.time.Instant.ofEpochMilli(key).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        map.put("doc_count", docCount);
        map.putAll(aggregations.toMap());
        return map;
    }
}
