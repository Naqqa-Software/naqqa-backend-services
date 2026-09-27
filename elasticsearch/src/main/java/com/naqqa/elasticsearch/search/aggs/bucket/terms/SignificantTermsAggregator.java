package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.BucketArrays;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class SignificantTermsAggregator extends BucketsAggregator {

    private final SortedSetValues source;
    private final long supersetSize;
    private final Function<String, Long> backgroundFrequency;
    private final SignificanceHeuristic heuristic;
    private final int size;
    private final int shardSize;
    private final long minDocCount;
    private long[] totalDocCounts = new long[4];

    public SignificantTermsAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, SortedSetValues source,
                                       long supersetSize, Function<String, Long> backgroundFrequency, SignificanceHeuristic heuristic,
                                       int size, int shardSize, long minDocCount) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.supersetSize = supersetSize;
        this.backgroundFrequency = backgroundFrequency;
        this.heuristic = heuristic;
        this.size = size;
        this.shardSize = shardSize;
        this.minDocCount = minDocCount;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        long supersetSize = ParamsHelper.getLong(ctx.params(), "background_doc_count", 1);
        Function<String, Long> backgroundFrequency = (Function<String, Long>) ctx.params().getOrDefault("background_frequency", (Function<String, Long>) s -> 0L);
        SignificanceHeuristic heuristic = SignificanceHeuristic.fromString(ParamsHelper.getString(ctx.params(), "heuristic", "jlh"));
        int size = ParamsHelper.getInt(ctx.params(), "size", 10);
        int shardSize = ParamsHelper.getInt(ctx.params(), "shard_size", Math.max(size + 10, size * 2));
        long minDocCount = ParamsHelper.getLong(ctx.params(), "min_doc_count", 3);
        return new SignificantTermsAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().bytesValues(field),
            supersetSize, backgroundFrequency, heuristic, size, shardSize, minDocCount);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        totalDocCounts = BucketArrays.grow(totalDocCounts, (int) bucketOrd + 1);
        totalDocCounts[(int) bucketOrd]++;
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            collectBucket(doc, bucketOrd, source.nextOrd());
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        long subsetSize = owningBucketOrd >= 0 && owningBucketOrd < totalDocCounts.length ? totalDocCounts[(int) owningBucketOrd] : 0;
        List<SignificantTermsBucket> candidates = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            String key = source.lookupOrd(bucketOrds.key(bucketOrd)).utf8ToString();
            long docCount = bucketDocCount(bucketOrd);
            if (docCount < minDocCount) {
                continue;
            }
            long supersetDf = backgroundFrequency.apply(key);
            double score = heuristic.score(Math.max(subsetSize, 1), docCount, Math.max(supersetSize, 1), supersetDf);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            candidates.add(new SignificantTermsBucket(key, docCount, supersetDf, score, subAggs));
        }
        candidates.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (candidates.size() > shardSize) {
            candidates = new ArrayList<>(candidates.subList(0, shardSize));
        }
        return new InternalSignificantTerms(name, candidates, subsetSize, supersetSize, heuristic, backgroundFrequency, size, minDocCount, null);
    }
}
