package com.naqqa.elasticsearch.search.aggs.bucket.range;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RangeAggregator extends BucketsAggregator {

    protected final DoubleValuesSource source;
    protected final List<RangeSpec> ranges;

    public RangeAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, DoubleValuesSource source, List<RangeSpec> ranges) {
        super(name, subAggregators, bucketConsumer);
        this.source = source;
        this.ranges = ranges;
    }

    public static List<RangeSpec> parseRanges(AggParseContext ctx) {
        List<Object> rangeList = ParamsHelper.asList(ctx.params().get("ranges"));
        List<RangeSpec> ranges = new ArrayList<>();
        for (Object o : rangeList) {
            Map<String, Object> r = ParamsHelper.asMap(o);
            Double from = r.containsKey("from") ? ((Number) r.get("from")).doubleValue() : null;
            Double to = r.containsKey("to") ? ((Number) r.get("to")).doubleValue() : null;
            String key = r.containsKey("key") ? String.valueOf(r.get("key")) : null;
            ranges.add(new RangeSpec(key, from, to));
        }
        return ranges;
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        return new RangeAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), ctx.lookup().doubleValues(field), parseRanges(ctx));
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        if (!source.advanceExact(doc)) {
            return;
        }
        int n = source.docValueCount();
        for (int i = 0; i < n; i++) {
            double v = source.nextValue();
            for (int r = 0; r < ranges.size(); r++) {
                if (ranges.get(r).matches(v)) {
                    collectBucket(doc, bucketOrd, r);
                }
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<RangeBucket> buckets = new ArrayList<>();
        for (int r = 0; r < ranges.size(); r++) {
            long bucketOrd = bucketOrds.find(owningBucketOrd, r);
            long docCount = bucketDocCount(bucketOrd);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new RangeBucket(ranges.get(r), docCount, subAggs));
        }
        return new InternalRange(name, buckets, null);
    }
}
