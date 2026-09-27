package com.naqqa.elasticsearch.search.aggs.bucket.range;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class IpRangeAggregator extends BucketsAggregator {

    private final SortedSetValues source;
    private final List<IpRangeSpec> ranges;

    public IpRangeAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, SortedSetValues source, List<IpRangeSpec> ranges) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.ranges = ranges;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        List<Object> rangeList = ParamsHelper.asList(ctx.params().get("ranges"));
        List<IpRangeSpec> ranges = new ArrayList<>();
        for (Object o : rangeList) {
            Map<String, Object> r = ParamsHelper.asMap(o);
            String key = r.containsKey("key") ? String.valueOf(r.get("key")) : null;
            String cidr = r.containsKey("mask") ? String.valueOf(r.get("mask")) : null;
            String from = r.containsKey("from") ? String.valueOf(r.get("from")) : null;
            String to = r.containsKey("to") ? String.valueOf(r.get("to")) : null;
            ranges.add(new IpRangeSpec(key, cidr, from, to));
        }
        return new IpRangeAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().bytesValues(field), ranges);
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            String ip = source.lookupOrd(source.nextOrd()).utf8ToString();
            for (int r = 0; r < ranges.size(); r++) {
                if (ranges.get(r).matches(ip)) {
                    collectBucket(doc, bucketOrd, r);
                }
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<IpRangeBucket> buckets = new ArrayList<>();
        for (int r = 0; r < ranges.size(); r++) {
            long bucketOrd = bucketOrds.find(owningBucketOrd, r);
            long docCount = bucketDocCount(bucketOrd);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new IpRangeBucket(ranges.get(r), docCount, subAggs));
        }
        return new InternalIpRange(name, buckets, null);
    }
}
