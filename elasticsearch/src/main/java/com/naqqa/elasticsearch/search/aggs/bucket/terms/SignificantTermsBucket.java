package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SignificantTermsBucket implements MultiBucketsAggregation.Bucket {

    private final String key;
    private final long docCount;
    private final long supersetDf;
    private final double score;
    private final InternalAggregations aggregations;

    public SignificantTermsBucket(String key, long docCount, long supersetDf, double score, InternalAggregations aggregations) {
        this.key = key;
        this.docCount = docCount;
        this.supersetDf = supersetDf;
        this.score = score;
        this.aggregations = aggregations;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public String getKeyAsString() {
        return key;
    }

    @Override
    public long getDocCount() {
        return docCount;
    }

    public long getSupersetDf() {
        return supersetDf;
    }

    public double getScore() {
        return score;
    }

    @Override
    public InternalAggregations getAggregations() {
        return aggregations;
    }

    @Override
    public SignificantTermsBucket withAggregations(InternalAggregations aggregations) {
        return new SignificantTermsBucket(key, docCount, supersetDf, score, aggregations);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("doc_count", docCount);
        map.put("score", score);
        map.put("bg_count", supersetDf);
        map.putAll(aggregations.toMap());
        return map;
    }
}
