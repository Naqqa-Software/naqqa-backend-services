package com.naqqa.elasticsearch.search.aggs.bucket.range;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DateRangeAggregator {

    private DateRangeAggregator() {
    }

    private static double toMillis(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Instant.parse(String.valueOf(o)).toEpochMilli();
    }

    public static Aggregator parse(AggParseContext ctx) {
        String field = ParamsHelper.requireString(ctx.params(), "field");
        List<Object> rangeList = ParamsHelper.asList(ctx.params().get("ranges"));
        List<RangeSpec> ranges = new ArrayList<>();
        for (Object o : rangeList) {
            Map<String, Object> r = ParamsHelper.asMap(o);
            Double from = r.containsKey("from") ? toMillis(r.get("from")) : null;
            Double to = r.containsKey("to") ? toMillis(r.get("to")) : null;
            String key = r.containsKey("key") ? String.valueOf(r.get("key")) : null;
            ranges.add(new RangeSpec(key, from, to));
        }
        DoubleValuesSource source = DoubleValuesSource.of(ctx.lookup().longValues(field), false);
        return new RangeAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), source, ranges);
    }
}
