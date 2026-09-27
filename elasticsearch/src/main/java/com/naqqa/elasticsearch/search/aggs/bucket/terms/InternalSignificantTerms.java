package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.MultiBucketsAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.bucket.BucketReduceUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class InternalSignificantTerms extends InternalAggregation implements MultiBucketsAggregation {

    private final List<SignificantTermsBucket> buckets;
    private final long subsetSize;
    private final long supersetSize;
    private final SignificanceHeuristic heuristic;
    private final Function<String, Long> backgroundFrequency;
    private final int requiredSize;
    private final long minDocCount;

    public InternalSignificantTerms(String name, List<SignificantTermsBucket> buckets, long subsetSize, long supersetSize,
                                     SignificanceHeuristic heuristic, Function<String, Long> backgroundFrequency, int requiredSize,
                                     long minDocCount, Map<String, Object> metadata) {
        super(name, metadata);
        this.buckets = buckets;
        this.subsetSize = subsetSize;
        this.supersetSize = supersetSize;
        this.heuristic = heuristic;
        this.backgroundFrequency = backgroundFrequency;
        this.requiredSize = requiredSize;
        this.minDocCount = minDocCount;
    }

    @Override
    public List<? extends Bucket> getBuckets() {
        return buckets;
    }

    @Override
    public InternalAggregation withBuckets(List<? extends Bucket> newBuckets) {
        List<SignificantTermsBucket> cast = new ArrayList<>(newBuckets.size());
        for (Bucket b : newBuckets) {
            cast.add((SignificantTermsBucket) b);
        }
        return new InternalSignificantTerms(getName(), cast, subsetSize, supersetSize, heuristic, backgroundFrequency, requiredSize, minDocCount, getMetadata());
    }

    @Override
    public String getType() {
        return "significant_terms";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        long subsetAcc = 0;
        long supersetAcc = 0;
        List<List<SignificantTermsBucket>> perShard = new ArrayList<>();
        for (InternalAggregation a : aggregations) {
            InternalSignificantTerms s = (InternalSignificantTerms) a;
            subsetAcc += s.subsetSize;
            supersetAcc = Math.max(supersetAcc, s.supersetSize);
            perShard.add(s.buckets);
        }
        LinkedHashMap<Object, List<SignificantTermsBucket>> grouped = new LinkedHashMap<>();
        for (List<SignificantTermsBucket> list : perShard) {
            for (SignificantTermsBucket b : list) {
                grouped.computeIfAbsent(b.getKey(), k -> new ArrayList<>()).add(b);
            }
        }
        List<SignificantTermsBucket> reduced = new ArrayList<>();
        for (List<SignificantTermsBucket> members : grouped.values()) {
            long docCount = 0;
            for (SignificantTermsBucket m : members) {
                docCount += m.getDocCount();
            }
            if (docCount < minDocCount) {
                continue;
            }
            long supersetDf = members.get(0).getSupersetDf();
            double score = heuristic.score(subsetAcc, docCount, supersetAcc, supersetDf);
            List<MultiBucketsAggregation.Bucket> asBuckets = new ArrayList<>(members);
            InternalAggregations subAggs = BucketReduceUtil.reduceSubAggs(asBuckets, context);
            reduced.add(new SignificantTermsBucket((String) members.get(0).getKey(), docCount, supersetDf, score, subAggs));
        }
        reduced.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (context.isFinalReduce() && reduced.size() > requiredSize) {
            reduced = new ArrayList<>(reduced.subList(0, requiredSize));
        }
        if (context.isFinalReduce()) {
            context.consumeBucketsAndMaybeBreak(reduced.size());
        }
        return new InternalSignificantTerms(getName(), reduced, subsetAcc, supersetAcc, heuristic, backgroundFrequency, requiredSize, minDocCount, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("doc_count", subsetSize);
        List<Object> list = new ArrayList<>();
        for (SignificantTermsBucket b : buckets) {
            list.add(b.toMap());
        }
        map.put("buckets", list);
        return map;
    }
}
