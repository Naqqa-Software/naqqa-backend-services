package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalDateHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsOrder;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMax;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMin;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalSum;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalValueCount;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InternalAggregationStreams {

    @FunctionalInterface
    public interface Writer {
        void write(StreamOutput out, InternalAggregation aggregation) throws IOException;
    }

    @FunctionalInterface
    public interface Reader {
        InternalAggregation read(StreamInput in, String name, Map<String, Object> metadata) throws IOException;
    }

    private static final Map<String, Writer> WRITERS = new HashMap<>();
    private static final Map<String, Reader> READERS = new HashMap<>();

    private InternalAggregationStreams() {
    }

    public static void register(String type, Writer writer, Reader reader) {
        WRITERS.put(type, writer);
        READERS.put(type, reader);
    }

    static {
        register("terms",
            (out, agg) -> writeTerms(out, (InternalTerms) agg),
            InternalAggregationStreams::readTerms);
        register("date_histogram",
            (out, agg) -> writeDateHistogram(out, (InternalDateHistogram) agg),
            InternalAggregationStreams::readDateHistogram);
        register("avg",
            (out, agg) -> {
                InternalAvg a = (InternalAvg) agg;
                out.writeDouble(a.sum());
                out.writeVLong(a.count());
            },
            (in, name, metadata) -> new InternalAvg(name, in.readDouble(), in.readVLong(), metadata));
        register("sum",
            (out, agg) -> out.writeDouble(((InternalSum) agg).value()),
            (in, name, metadata) -> new InternalSum(name, in.readDouble(), metadata));
        register("min",
            (out, agg) -> out.writeDouble(((InternalMin) agg).value()),
            (in, name, metadata) -> new InternalMin(name, in.readDouble(), metadata));
        register("max",
            (out, agg) -> out.writeDouble(((InternalMax) agg).value()),
            (in, name, metadata) -> new InternalMax(name, in.readDouble(), metadata));
        register("value_count",
            (out, agg) -> out.writeVLong(((InternalValueCount) agg).count()),
            (in, name, metadata) -> new InternalValueCount(name, in.readVLong(), metadata));
    }

    public static void writeAggregations(StreamOutput out, InternalAggregations aggregations) throws IOException {
        List<InternalAggregation> list = aggregations.aggregations();
        out.writeVInt(list.size());
        for (InternalAggregation agg : list) {
            writeAggregation(out, agg);
        }
    }

    public static InternalAggregations readAggregations(StreamInput in) throws IOException {
        int count = in.readVInt();
        List<InternalAggregation> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(readAggregation(in));
        }
        return new InternalAggregations(list);
    }

    public static void writeAggregation(StreamOutput out, InternalAggregation agg) throws IOException {
        String type = agg.getType();
        Writer writer = WRITERS.get(type);
        if (writer == null) {
            throw new IOException("no wire writer registered for aggregation type [" + type + "]");
        }
        out.writeString(type);
        out.writeString(agg.getName());
        out.writeGenericValue(agg.getMetadata());
        writer.write(out, agg);
    }

    @SuppressWarnings("unchecked")
    public static InternalAggregation readAggregation(StreamInput in) throws IOException {
        String type = in.readString();
        String name = in.readString();
        Map<String, Object> metadata = (Map<String, Object>) in.readGenericValue();
        Reader reader = READERS.get(type);
        if (reader == null) {
            throw new IOException("no wire reader registered for aggregation type [" + type + "]");
        }
        return reader.read(in, name, metadata);
    }

    private static void writeTerms(StreamOutput out, InternalTerms terms) throws IOException {
        List<? extends MultiBucketsAggregation.Bucket> buckets = terms.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            TermsBucket tb = (TermsBucket) b;
            out.writeGenericValue(tb.getKey());
            out.writeVLong(tb.getDocCount());
            out.writeVLong(tb.getDocCountError());
            writeAggregations(out, tb.getAggregations());
        }
        out.writeVLong(terms.getSumOtherDocCount());
        out.writeVInt(terms.getRequiredSize());
        out.writeVLong(terms.getMinDocCount());
        out.writeString(terms.getOrder().path);
        out.writeBoolean(terms.getOrder().ascending);
        out.writeBoolean(terms.isShowTermDocCountError());
    }

    private static InternalTerms readTerms(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<TermsBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object key = in.readGenericValue();
            long docCount = in.readVLong();
            long docCountError = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new TermsBucket(key, docCount, docCountError, subAggs));
        }
        long sumOtherDocCount = in.readVLong();
        int requiredSize = in.readVInt();
        long minDocCount = in.readVLong();
        String orderPath = in.readString();
        boolean ascending = in.readBoolean();
        TermsOrder order = new TermsOrder(orderPath, ascending);
        boolean showTermDocCountError = in.readBoolean();
        return new InternalTerms(name, buckets, sumOtherDocCount, requiredSize, minDocCount, order, showTermDocCountError, metadata);
    }

    private static void writeDateHistogram(StreamOutput out, InternalDateHistogram hist) throws IOException {
        List<? extends MultiBucketsAggregation.Bucket> buckets = hist.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            DateHistogramBucket db = (DateHistogramBucket) b;
            out.writeZLong(db.getKey());
            out.writeVLong(db.getDocCount());
            writeAggregations(out, db.getAggregations());
        }
        CalendarInterval calendarInterval = hist.getCalendarInterval();
        out.writeBoolean(calendarInterval != null);
        if (calendarInterval != null) {
            out.writeString(calendarInterval.name());
        }
        out.writeVLong(hist.getFixedIntervalMillis());
        out.writeString(hist.getZone().getId());
        out.writeZLong(hist.getOffsetMillis());
        out.writeVLong(hist.getMinDocCount());
        out.writeOptionalLong(hist.getBoundsMin());
        out.writeOptionalLong(hist.getBoundsMax());
    }

    private static InternalDateHistogram readDateHistogram(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<DateHistogramBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long key = in.readZLong();
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new DateHistogramBucket(key, docCount, subAggs));
        }
        CalendarInterval calendarInterval = null;
        if (in.readBoolean()) {
            calendarInterval = CalendarInterval.valueOf(in.readString());
        }
        long fixedIntervalMillis = in.readVLong();
        ZoneId zone = ZoneId.of(in.readString());
        long offsetMillis = in.readZLong();
        long minDocCount = in.readVLong();
        Long boundsMin = in.readOptionalLong();
        Long boundsMax = in.readOptionalLong();
        return new InternalDateHistogram(name, buckets, calendarInterval, fixedIntervalMillis, zone, offsetMillis, minDocCount, boundsMin, boundsMax, metadata);
    }
}
