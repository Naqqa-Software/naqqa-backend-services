package com.naqqa.elasticsearch.search.aggs.bucket.composite;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.LongValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CompositeAggregator extends BucketsAggregator {

    private final List<CompositeSource> sources;
    private final int size;
    private final List<Object> afterKey;
    private final Map<List<Object>, Long> keyIds = new HashMap<>();
    private final List<List<Object>> idToKey = new ArrayList<>();

    public CompositeAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, List<CompositeSource> sources,
                                int size, List<Object> afterKey) {
        super(name, subAggregators, bucketConsumer);
        this.sources = sources;
        this.size = size;
        this.afterKey = afterKey;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        List<Object> sourceSpecs = ParamsHelper.asList(ctx.params().get("sources"));
        List<CompositeSource> sources = new ArrayList<>();
        for (Object o : sourceSpecs) {
            Map<String, Object> outer = ParamsHelper.asMap(o);
            Map.Entry<String, Object> entry = outer.entrySet().iterator().next();
            String sourceName = entry.getKey();
            Map<String, Object> spec = ParamsHelper.asMap(entry.getValue());
            Map<String, Object> inner = ParamsHelper.asMap(spec.values().iterator().next());
            String type = spec.keySet().iterator().next();
            String field = ParamsHelper.requireString(inner, "field");
            boolean ascending = !"desc".equals(ParamsHelper.getString(inner, "order", "asc"));
            sources.add(buildSource(ctx, sourceName, type, field, inner, ascending));
        }
        int size = ParamsHelper.getInt(ctx.params(), "size", 10);
        List<Object> afterKey = null;
        Object after = ctx.params().get("after");
        if (after != null) {
            Map<String, Object> afterMap = ParamsHelper.asMap(after);
            afterKey = new ArrayList<>();
            for (CompositeSource s : sources) {
                afterKey.add(afterMap.get(s.name));
            }
        }
        return new CompositeAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), sources, size, afterKey);
    }

    private static CompositeSource buildSource(AggParseContext ctx, String name, String type, String field, Map<String, Object> inner, boolean ascending) {
        switch (type) {
            case "terms" -> {
                SortedSetValues values = ctx.lookup().bytesValues(field);
                return new CompositeSource(name, ascending, doc -> {
                    if (!values.advanceExact(doc) || values.docValueCount() == 0) {
                        return null;
                    }
                    return values.lookupOrd(values.nextOrd()).utf8ToString();
                });
            }
            case "histogram" -> {
                double interval = ParamsHelper.getDouble(inner, "interval", 1.0);
                DoubleValuesSource values = ctx.lookup().doubleValues(field);
                return new CompositeSource(name, ascending, doc -> {
                    if (!values.advanceExact(doc) || values.docValueCount() == 0) {
                        return null;
                    }
                    return Math.floor(values.nextValue() / interval) * interval;
                });
            }
            case "date_histogram" -> {
                CalendarInterval calendarInterval = CalendarInterval.fromString(ParamsHelper.getString(inner, "calendar_interval", "day"));
                ZoneId zone = ZoneId.of(ParamsHelper.getString(inner, "time_zone", "UTC"));
                LongValuesSource values = ctx.lookup().longValues(field);
                return new CompositeSource(name, ascending, doc -> {
                    if (!values.advanceExact(doc) || values.docValueCount() == 0) {
                        return null;
                    }
                    return calendarInterval.truncateToMillis(values.nextValue(), zone);
                });
            }
            default -> throw new IllegalArgumentException("unknown composite source type [" + type + "]");
        }
    }

    private long idFor(List<Object> key) {
        Long id = keyIds.get(key);
        if (id == null) {
            id = (long) idToKey.size();
            idToKey.add(key);
            keyIds.put(key, id);
        }
        return id;
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        List<Object> key = new ArrayList<>(sources.size());
        for (CompositeSource source : sources) {
            Object v = source.valueOf.apply(doc);
            if (v == null) {
                return;
            }
            key.add(v);
        }
        collectBucket(doc, bucketOrd, idFor(key));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareKeys(List<Object> a, List<Object> b) {
        for (int i = 0; i < a.size(); i++) {
            int cmp = ((Comparable) a.get(i)).compareTo(b.get(i));
            if (cmp != 0) {
                return sources.get(i).ascending ? cmp : -cmp;
            }
        }
        return 0;
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<CompositeBucket> candidates = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        for (CompositeSource s : sources) {
            sourceNames.add(s.name);
        }
        var ords = bucketOrds.ordsFor(owningBucketOrd);
        for (int i = 0; i < ords.size(); i++) {
            long bucketOrd = ords.get(i);
            List<Object> key = idToKey.get((int) bucketOrds.key(bucketOrd));
            if (afterKey != null && compareKeys(key, afterKey) <= 0) {
                continue;
            }
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            candidates.add(new CompositeBucket(sourceNames, key, bucketDocCount(bucketOrd), subAggs));
        }
        candidates.sort((a, b) -> compareKeys((List<Object>) a.getKey(), (List<Object>) b.getKey()));
        if (candidates.size() > size) {
            candidates = new ArrayList<>(candidates.subList(0, size));
        }
        List<Boolean> ascending = new ArrayList<>();
        for (CompositeSource s : sources) {
            ascending.add(s.ascending);
        }
        return new InternalComposite(name, candidates, size, ascending, null);
    }
}
