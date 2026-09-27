package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MultiTermsAggregator extends BucketsAggregator {

    private final SortedSetValues[] sources;
    private final int size;
    private final int shardSize;
    private final long minDocCount;
    private final TermsOrder order;
    private final Map<List<String>, Long> keyIds = new HashMap<>();
    private final List<List<String>> idToKey = new ArrayList<>();

    public MultiTermsAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, SortedSetValues[] sources,
                                 int size, int shardSize, long minDocCount, TermsOrder order) {
        super(name, subAggregators, bucketConsumer);
        this.sources = sources;
        this.size = size;
        this.shardSize = shardSize;
        this.minDocCount = minDocCount;
        this.order = order;
    }

    public static Aggregator parse(AggParseContext ctx) {
        List<Object> fields = ParamsHelper.asList(ctx.params().get("fields"));
        SortedSetValues[] sources = new SortedSetValues[fields.size()];
        for (int i = 0; i < sources.length; i++) {
            sources[i] = ctx.lookup().bytesValues(String.valueOf(fields.get(i)));
        }
        int size = ParamsHelper.getInt(ctx.params(), "size", 10);
        int shardSize = ParamsHelper.getInt(ctx.params(), "shard_size", Math.max(size + 10, size * 2));
        long minDocCount = ParamsHelper.getLong(ctx.params(), "min_doc_count", 1);
        TermsOrder order = TermsOrder.parse(ctx.params().get("order"));
        return new MultiTermsAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), sources, size, shardSize, minDocCount, order);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        List<List<String>> perField = new ArrayList<>(sources.length);
        for (SortedSetValues source : sources) {
            if (!source.advanceExact(doc)) {
                return;
            }
            List<String> values = new ArrayList<>();
            int n = source.docValueCount();
            for (int i = 0; i < n; i++) {
                values.add(source.lookupOrd(source.nextOrd()).utf8ToString());
            }
            if (values.isEmpty()) {
                return;
            }
            perField.add(values);
        }
        List<String> combo = new ArrayList<>(sources.length);
        combine(perField, 0, combo, doc, bucketOrd);
    }

    private void combine(List<List<String>> perField, int idx, List<String> combo, int doc, long bucketOrd) {
        if (idx == perField.size()) {
            List<String> key = new ArrayList<>(combo);
            Long id = keyIds.get(key);
            if (id == null) {
                id = (long) idToKey.size();
                idToKey.add(key);
                keyIds.put(key, id);
            }
            collectBucket(doc, bucketOrd, id);
            return;
        }
        for (String v : perField.get(idx)) {
            combo.add(v);
            combine(perField, idx + 1, combo, doc, bucketOrd);
            combo.remove(combo.size() - 1);
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<TermsBucket> candidates = new ArrayList<>();
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            List<String> key = idToKey.get((int) bucketOrds.key(bucketOrd));
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
        return new InternalTerms(name, kept, sumOther, size, minDocCount, order, false, null);
    }
}
