package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.IncludeExclude;
import com.naqqa.elasticsearch.search.aggs.support.LongValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class TermsAggregator extends BucketsAggregator {

    private final SortedSetValues bytesSource;
    private final LongValuesSource longSource;
    private final boolean floatingPoint;
    private final Predicate<String> filter;
    private final int size;
    private final int shardSize;
    private final long minDocCount;
    private final TermsOrder order;
    private final boolean showTermDocCountError;

    public TermsAggregator(String name, Aggregator[] subAggregators, com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer bucketConsumer,
                            SortedSetValues bytesSource, LongValuesSource longSource, boolean floatingPoint, Predicate<String> filter,
                            int size, int shardSize, long minDocCount, TermsOrder order, boolean showTermDocCountError) {
        super(name, subAggregators, bucketConsumer);
        this.bytesSource = bytesSource;
        this.longSource = longSource;
        this.floatingPoint = floatingPoint;
        this.filter = filter;
        this.size = size;
        this.shardSize = shardSize;
        this.minDocCount = minDocCount;
        this.order = order;
        this.showTermDocCountError = showTermDocCountError;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        String fieldType = ParamsHelper.getString(ctx.params(), "field_type", "keyword");
        int size = ParamsHelper.getInt(ctx.params(), "size", 10);
        int shardSize = ParamsHelper.getInt(ctx.params(), "shard_size", Math.max(size + 10, size * 2));
        long minDocCount = ParamsHelper.getLong(ctx.params(), "min_doc_count", 1);
        TermsOrder order = TermsOrder.parse(ctx.params().get("order"));
        boolean showError = ParamsHelper.getBoolean(ctx.params(), "show_term_doc_count_error", false);
        Predicate<String> filter = IncludeExclude.parse(ctx.params());
        if ("numeric".equals(fieldType)) {
            boolean fp = ctx.lookup().isFloatingPoint(field);
            return new TermsAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), null, ctx.lookup().longValues(field), fp,
                filter, size, shardSize, minDocCount, order, showError);
        }
        return new TermsAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().bytesValues(field), null, false,
            filter, size, shardSize, minDocCount, order, showError);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (bytesSource != null) {
            if (!bytesSource.advanceExact(doc)) {
                return;
            }
            int n = bytesSource.docValueCount();
            for (int i = 0; i < n; i++) {
                long ord = bytesSource.nextOrd();
                if (filter != null) {
                    BytesRef ref = bytesSource.lookupOrd(ord);
                    if (!filter.test(ref.utf8ToString())) {
                        continue;
                    }
                }
                collectBucket(doc, bucketOrd, ord);
            }
        } else {
            if (!longSource.advanceExact(doc)) {
                return;
            }
            int n = longSource.docValueCount();
            for (int i = 0; i < n; i++) {
                long v = longSource.nextValue();
                collectBucket(doc, bucketOrd, v);
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<TermsBucket> candidates = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            long rawKey = bucketOrds.key(bucketOrd);
            Object key = resolveKey(rawKey);
            long docCount = bucketDocCount(bucketOrd);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            candidates.add(new TermsBucket(key, docCount, 0, subAggs));
        }
        candidates.sort(order.comparator());
        List<TermsBucket> kept = new ArrayList<>();
        long sumOther = 0;
        for (TermsBucket b : candidates) {
            if (kept.size() < shardSize) {
                kept.add(b);
            } else {
                sumOther += b.getDocCount();
            }
        }
        return new InternalTerms(name, kept, sumOther, size, minDocCount, order, showTermDocCountError, null);
    }

    private Object resolveKey(long rawKey) {
        if (bytesSource != null) {
            return bytesSource.lookupOrd(rawKey).utf8ToString();
        }
        if (floatingPoint) {
            return com.naqqa.elasticsearch.codec.NumericUtils.sortableLongToDouble(rawKey);
        }
        return rawKey;
    }
}
