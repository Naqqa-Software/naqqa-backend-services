package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.IncludeExclude;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class RareTermsAggregator extends BucketsAggregator {

    private final SortedSetValues source;
    private final Predicate<String> filter;
    private final long maxDocCount;

    public RareTermsAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, SortedSetValues source,
                                Predicate<String> filter, long maxDocCount) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.filter = filter;
        this.maxDocCount = maxDocCount;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        long maxDocCount = ParamsHelper.getLong(ctx.params(), "max_doc_count", 1);
        Predicate<String> filter = IncludeExclude.parse(ctx.params());
        return new RareTermsAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().bytesValues(field), filter, maxDocCount);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            long ord = source.nextOrd();
            if (filter != null) {
                BytesRef ref = source.lookupOrd(ord);
                if (!filter.test(ref.utf8ToString())) {
                    continue;
                }
            }
            collectBucket(doc, bucketOrd, ord);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<TermsBucket> buckets = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            long docCount = bucketDocCount(bucketOrd);
            if (docCount > maxDocCount) {
                continue;
            }
            String key = source.lookupOrd(bucketOrds.key(bucketOrd)).utf8ToString();
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new TermsBucket(key, docCount, 0, subAggs));
        }
        buckets.sort((a, b) -> Long.compare(a.getDocCount(), b.getDocCount()));
        return new InternalRareTerms(name, buckets, maxDocCount, null);
    }
}
