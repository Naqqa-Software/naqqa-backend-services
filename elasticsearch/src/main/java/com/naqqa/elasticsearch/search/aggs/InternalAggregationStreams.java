package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.common.io.stream.StreamInput;
import com.naqqa.elasticsearch.common.io.stream.StreamOutput;
import com.naqqa.elasticsearch.search.aggs.bucket.composite.CompositeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.composite.InternalComposite;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.FiltersBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalFilters;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.CalendarInterval;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.HistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalDateHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.InternalVariableWidthHistogram;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.VariableWidthHistogramBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.InternalIpRange;
import com.naqqa.elasticsearch.search.aggs.bucket.range.InternalRange;
import com.naqqa.elasticsearch.search.aggs.bucket.range.IpRangeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.IpRangeSpec;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeSpec;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalRareTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalSignificantTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.SignificanceHeuristic;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.SignificantTermsBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsOrder;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalBoxplot;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalCardinality;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalExtendedStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalGeoBounds;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalGeoCentroid;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMatrixStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMax;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMedianAbsoluteDeviation;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalMin;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalPercentileRanks;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalPercentiles;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalRate;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalScriptedMetric;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalStringStats;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalSum;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalTTest;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalTopHits;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalTopMetrics;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalValueCount;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalWeightedAvg;
import com.naqqa.elasticsearch.search.aggs.metrics.MatrixStatsState;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentilesMethod;
import com.naqqa.elasticsearch.search.aggs.support.sketch.DoubleHdrHistogram;
import com.naqqa.elasticsearch.search.aggs.support.sketch.HyperLogLogPlusPlus;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TDigest;
import com.naqqa.elasticsearch.search.aggs.support.sketch.TTest;

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

        register("cardinality",
            (out, agg) -> out.writeByteArray(((InternalCardinality) agg).counts().toBytes(0)),
            (in, name, metadata) -> new InternalCardinality(name, HyperLogLogPlusPlus.fromBytes(in.readByteArray()), metadata));

        register("percentiles",
            InternalAggregationStreams::writePercentiles,
            InternalAggregationStreams::readPercentiles);
        register("percentile_ranks",
            InternalAggregationStreams::writePercentileRanks,
            InternalAggregationStreams::readPercentileRanks);

        register("stats",
            (out, agg) -> writeStats(out, (InternalStats) agg),
            (in, name, metadata) -> {
                long count = in.readVLong();
                double sum = in.readDouble();
                double min = in.readDouble();
                double max = in.readDouble();
                return new InternalStats(name, count, sum, min, max, metadata);
            });
        register("extended_stats",
            (out, agg) -> {
                InternalExtendedStats s = (InternalExtendedStats) agg;
                writeStats(out, s);
                out.writeDouble(s.sumOfSquares());
                out.writeDouble(sigmaOf(s));
            },
            (in, name, metadata) -> {
                long count = in.readVLong();
                double sum = in.readDouble();
                double min = in.readDouble();
                double max = in.readDouble();
                double sumOfSquares = in.readDouble();
                double sigma = in.readDouble();
                return new InternalExtendedStats(name, count, sum, min, max, sumOfSquares, sigma, metadata);
            });

        register("top_hits",
            InternalAggregationStreams::writeTopHits,
            InternalAggregationStreams::readTopHits);
        register("top_metrics",
            InternalAggregationStreams::writeTopMetrics,
            InternalAggregationStreams::readTopMetrics);

        register("geo_bounds",
            (out, agg) -> {
                InternalGeoBounds g = (InternalGeoBounds) agg;
                out.writeDouble(geoBoundsTop(g));
                out.writeDouble(geoBoundsBottom(g));
                out.writeDouble(geoBoundsLeft(g));
                out.writeDouble(geoBoundsRight(g));
            },
            (in, name, metadata) -> new InternalGeoBounds(name, in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), metadata));
        register("geo_centroid",
            (out, agg) -> {
                InternalGeoCentroid g = (InternalGeoCentroid) agg;
                out.writeDouble(geoCentroidLatSum(g));
                out.writeDouble(geoCentroidLonSum(g));
                out.writeVLong(g.count());
            },
            (in, name, metadata) -> new InternalGeoCentroid(name, in.readDouble(), in.readDouble(), in.readVLong(), metadata));

        register("scripted_metric",
            (out, agg) -> out.writeGenericValue(((InternalScriptedMetric) agg).values()),
            (in, name, metadata) -> {
                @SuppressWarnings("unchecked")
                List<Object> values = (List<Object>) in.readGenericValue();
                return new InternalScriptedMetric(name, values, null, metadata);
            });

        register("string_stats",
            InternalAggregationStreams::writeStringStats,
            InternalAggregationStreams::readStringStats);

        register("boxplot",
            (out, agg) -> out.writeByteArray(((InternalBoxplot) agg).digestValue().toBytes()),
            (in, name, metadata) -> new InternalBoxplot(name, TDigest.fromBytes(in.readByteArray()), metadata));

        register("rate",
            (out, agg) -> {
                InternalRate r = (InternalRate) agg;
                out.writeDouble(rateSum(r));
                out.writeDouble(r.divisorValue());
            },
            (in, name, metadata) -> new InternalRate(name, in.readDouble(), in.readDouble(), metadata));

        register("t_test",
            (out, agg) -> out.writeByteArray(((InternalTTest) agg).testValue().toBytes()),
            (in, name, metadata) -> new InternalTTest(name, TTest.fromBytes(in.readByteArray()), metadata));

        register("matrix_stats",
            InternalAggregationStreams::writeMatrixStats,
            InternalAggregationStreams::readMatrixStats);

        register("weighted_avg",
            (out, agg) -> {
                InternalWeightedAvg w = (InternalWeightedAvg) agg;
                out.writeDouble(weightedValueSum(w));
                out.writeDouble(weightSum(w));
            },
            (in, name, metadata) -> new InternalWeightedAvg(name, in.readDouble(), in.readDouble(), metadata));

        register("median_absolute_deviation",
            (out, agg) -> out.writeByteArray(((InternalMedianAbsoluteDeviation) agg).digestValue().toBytes()),
            (in, name, metadata) -> new InternalMedianAbsoluteDeviation(name, TDigest.fromBytes(in.readByteArray()), metadata));

        register("histogram",
            InternalAggregationStreams::writeHistogram,
            InternalAggregationStreams::readHistogram);
        register("variable_width_histogram",
            InternalAggregationStreams::writeVariableWidthHistogram,
            InternalAggregationStreams::readVariableWidthHistogram);
        register("range",
            InternalAggregationStreams::writeRange,
            InternalAggregationStreams::readRange);
        register("ip_range",
            InternalAggregationStreams::writeIpRange,
            InternalAggregationStreams::readIpRange);
        register("filters",
            InternalAggregationStreams::writeFilters,
            InternalAggregationStreams::readFilters);

        for (String singleBucketType : List.of("filter", "global", "missing", "nested", "reverse_nested",
            "children", "parent", "sampler", "diversified_sampler", "random_sampler")) {
            register(singleBucketType,
                InternalAggregationStreams::writeSingleBucket,
                (in, name, metadata) -> readSingleBucket(in, name, singleBucketType, metadata));
        }

        register("rare_terms",
            InternalAggregationStreams::writeRareTerms,
            InternalAggregationStreams::readRareTerms);
        register("significant_terms",
            InternalAggregationStreams::writeSignificantTerms,
            InternalAggregationStreams::readSignificantTerms);
        register("composite",
            InternalAggregationStreams::writeComposite,
            InternalAggregationStreams::readComposite);
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

    private static void writeStats(StreamOutput out, InternalStats s) throws IOException {
        out.writeVLong(s.count());
        out.writeDouble(s.sum());
        out.writeDouble(s.min());
        out.writeDouble(s.max());
    }

    private static double sigmaOf(InternalExtendedStats s) {
        return s.sigmaValue();
    }

    private static void writePercentiles(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalPercentiles p = (InternalPercentiles) agg;
        out.writeString(p.methodValue().name());
        double[] percents = p.percentsValue();
        out.writeVInt(percents.length);
        for (double v : percents) {
            out.writeDouble(v);
        }
        if (p.methodValue() == PercentilesMethod.TDIGEST) {
            out.writeByteArray(p.digestValue().toBytes());
        } else {
            out.writeByteArray(p.histogramValue().toBytes());
        }
    }

    private static InternalAggregation readPercentiles(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        PercentilesMethod method = PercentilesMethod.valueOf(in.readString());
        int n = in.readVInt();
        double[] percents = new double[n];
        for (int i = 0; i < n; i++) {
            percents[i] = in.readDouble();
        }
        byte[] bytes = in.readByteArray();
        if (method == PercentilesMethod.TDIGEST) {
            return new InternalPercentiles(name, method, percents, TDigest.fromBytes(bytes), null, metadata);
        }
        return new InternalPercentiles(name, method, percents, null, DoubleHdrHistogram.fromBytes(bytes), metadata);
    }

    private static void writePercentileRanks(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalPercentileRanks p = (InternalPercentileRanks) agg;
        out.writeString(p.methodValue().name());
        double[] values = p.valuesValue();
        out.writeVInt(values.length);
        for (double v : values) {
            out.writeDouble(v);
        }
        if (p.methodValue() == PercentilesMethod.TDIGEST) {
            out.writeByteArray(p.digestValue().toBytes());
        } else {
            out.writeByteArray(p.histogramValue().toBytes());
        }
    }

    private static InternalAggregation readPercentileRanks(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        PercentilesMethod method = PercentilesMethod.valueOf(in.readString());
        int n = in.readVInt();
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = in.readDouble();
        }
        byte[] bytes = in.readByteArray();
        if (method == PercentilesMethod.TDIGEST) {
            return new InternalPercentileRanks(name, method, values, TDigest.fromBytes(bytes), null, metadata);
        }
        return new InternalPercentileRanks(name, method, values, null, DoubleHdrHistogram.fromBytes(bytes), metadata);
    }

    private static void writeTopHits(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalTopHits th = (InternalTopHits) agg;
        out.writeVInt(th.sizeValue());
        out.writeBoolean(th.ascendingValue());
        out.writeVInt(th.hits().size());
        for (InternalTopHits.Hit h : th.hits()) {
            out.writeDouble(h.sortValue());
            out.writeGenericValue(h.source());
        }
    }

    @SuppressWarnings("unchecked")
    private static InternalAggregation readTopHits(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int size = in.readVInt();
        boolean ascending = in.readBoolean();
        int count = in.readVInt();
        List<InternalTopHits.Hit> hits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double sortValue = in.readDouble();
            Map<String, Object> source = (Map<String, Object>) in.readGenericValue();
            hits.add(new InternalTopHits.Hit(sortValue, source));
        }
        return new InternalTopHits(name, size, ascending, hits, metadata);
    }

    private static void writeTopMetrics(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalTopMetrics tm = (InternalTopMetrics) agg;
        out.writeVInt(tm.sizeValue());
        out.writeBoolean(tm.ascendingValue());
        out.writeVInt(tm.topMetrics().size());
        for (InternalTopMetrics.TopMetric m : tm.topMetrics()) {
            out.writeDouble(m.sortValue());
            out.writeVInt(m.metrics().size());
            for (Map.Entry<String, Double> e : m.metrics().entrySet()) {
                out.writeString(e.getKey());
                out.writeDouble(e.getValue());
            }
        }
    }

    private static InternalAggregation readTopMetrics(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int size = in.readVInt();
        boolean ascending = in.readBoolean();
        int count = in.readVInt();
        List<InternalTopMetrics.TopMetric> topMetrics = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double sortValue = in.readDouble();
            int metricCount = in.readVInt();
            Map<String, Double> metrics = new java.util.LinkedHashMap<>();
            for (int j = 0; j < metricCount; j++) {
                metrics.put(in.readString(), in.readDouble());
            }
            topMetrics.add(new InternalTopMetrics.TopMetric(sortValue, metrics));
        }
        return new InternalTopMetrics(name, size, ascending, topMetrics, metadata);
    }

    private static double geoBoundsTop(InternalGeoBounds g) {
        return g.boundsValues()[0];
    }

    private static double geoBoundsBottom(InternalGeoBounds g) {
        return g.boundsValues()[1];
    }

    private static double geoBoundsLeft(InternalGeoBounds g) {
        return g.boundsValues()[2];
    }

    private static double geoBoundsRight(InternalGeoBounds g) {
        return g.boundsValues()[3];
    }

    private static double geoCentroidLatSum(InternalGeoCentroid g) {
        return g.sums()[0];
    }

    private static double geoCentroidLonSum(InternalGeoCentroid g) {
        return g.sums()[1];
    }

    private static void writeStringStats(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalStringStats s = (InternalStringStats) agg;
        out.writeVLong(s.countValue());
        out.writeVLong(s.minLengthValue());
        out.writeVLong(s.maxLengthValue());
        out.writeDouble(s.sumLengthValue());
        long[] freq = s.charFreqValue();
        out.writeVInt(freq.length);
        for (long f : freq) {
            out.writeVLong(f);
        }
        out.writeBoolean(s.showDistributionValue());
    }

    private static InternalAggregation readStringStats(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        long count = in.readVLong();
        long minLength = in.readVLong();
        long maxLength = in.readVLong();
        double sumLength = in.readDouble();
        int n = in.readVInt();
        long[] freq = new long[n];
        for (int i = 0; i < n; i++) {
            freq[i] = in.readVLong();
        }
        boolean showDistribution = in.readBoolean();
        return new InternalStringStats(name, count, minLength, maxLength, sumLength, freq, showDistribution, metadata);
    }

    private static double rateSum(InternalRate r) {
        return r.sumValue();
    }

    private static void writeMatrixStats(StreamOutput out, InternalAggregation agg) throws IOException {
        MatrixStatsState state = ((InternalMatrixStats) agg).state();
        String[] fields = state.fieldsValue();
        out.writeVInt(fields.length);
        for (String f : fields) {
            out.writeString(f);
        }
        out.writeVLong(state.countValue());
        for (double m : state.meansValue()) {
            out.writeDouble(m);
        }
        for (double[] row : state.comomentsValue()) {
            for (double v : row) {
                out.writeDouble(v);
            }
        }
    }

    private static InternalAggregation readMatrixStats(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int n = in.readVInt();
        String[] fields = new String[n];
        for (int i = 0; i < n; i++) {
            fields[i] = in.readString();
        }
        long count = in.readVLong();
        double[] means = new double[n];
        for (int i = 0; i < n; i++) {
            means[i] = in.readDouble();
        }
        double[][] comoments = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                comoments[i][j] = in.readDouble();
            }
        }
        MatrixStatsState state = new MatrixStatsState(fields, count, means, comoments);
        return new InternalMatrixStats(name, state, metadata);
    }

    private static double weightedValueSum(InternalWeightedAvg w) {
        return w.weightedValueSumValue();
    }

    private static double weightSum(InternalWeightedAvg w) {
        return w.weightSumValue();
    }

    private static void writeHistogram(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalHistogram h = (InternalHistogram) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = h.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            HistogramBucket hb = (HistogramBucket) b;
            out.writeDouble((double) hb.getKey());
            out.writeVLong(hb.getDocCount());
            writeAggregations(out, hb.getAggregations());
        }
        out.writeDouble(h.intervalValue());
        out.writeDouble(h.offsetValue());
        out.writeVLong(h.getMinDocCount());
        writeOptionalDouble(out, h.getBoundsMin());
        writeOptionalDouble(out, h.getBoundsMax());
    }

    private static InternalAggregation readHistogram(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<HistogramBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double key = in.readDouble();
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new HistogramBucket(key, docCount, subAggs));
        }
        double interval = in.readDouble();
        double offset = in.readDouble();
        long minDocCount = in.readVLong();
        Double boundsMin = readOptionalDouble(in);
        Double boundsMax = readOptionalDouble(in);
        return new InternalHistogram(name, buckets, interval, offset, minDocCount, boundsMin, boundsMax, metadata);
    }

    private static void writeVariableWidthHistogram(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalVariableWidthHistogram h = (InternalVariableWidthHistogram) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = h.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            VariableWidthHistogramBucket vb = (VariableWidthHistogramBucket) b;
            out.writeDouble((double) vb.getKey());
            out.writeDouble(vb.getMin());
            out.writeDouble(vb.getMax());
            out.writeVLong(vb.getDocCount());
            writeAggregations(out, vb.getAggregations());
        }
        out.writeVInt(h.targetBucketsValue());
    }

    private static InternalAggregation readVariableWidthHistogram(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<VariableWidthHistogramBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double key = in.readDouble();
            double min = in.readDouble();
            double max = in.readDouble();
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new VariableWidthHistogramBucket(key, min, max, docCount, subAggs));
        }
        int targetBuckets = in.readVInt();
        return new InternalVariableWidthHistogram(name, buckets, targetBuckets, metadata);
    }

    private static void writeRange(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalRange r = (InternalRange) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = r.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            RangeBucket rb = (RangeBucket) b;
            RangeSpec spec = rb.spec();
            out.writeOptionalString(spec.key());
            writeOptionalDouble(out, spec.from());
            writeOptionalDouble(out, spec.to());
            out.writeVLong(rb.getDocCount());
            writeAggregations(out, rb.getAggregations());
        }
    }

    private static InternalAggregation readRange(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<RangeBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = in.readOptionalString();
            Double from = readOptionalDouble(in);
            Double to = readOptionalDouble(in);
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new RangeBucket(new RangeSpec(key, from, to), docCount, subAggs));
        }
        return new InternalRange(name, buckets, metadata);
    }

    private static void writeIpRange(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalIpRange r = (InternalIpRange) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = r.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            IpRangeBucket rb = (IpRangeBucket) b;
            IpRangeSpec spec = rb.spec();
            out.writeOptionalString(spec.key());
            out.writeOptionalString(spec.cidr());
            out.writeOptionalString(spec.from());
            out.writeOptionalString(spec.to());
            out.writeVLong(rb.getDocCount());
            writeAggregations(out, rb.getAggregations());
        }
    }

    private static InternalAggregation readIpRange(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<IpRangeBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = in.readOptionalString();
            String cidr = in.readOptionalString();
            String from = in.readOptionalString();
            String to = in.readOptionalString();
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new IpRangeBucket(new IpRangeSpec(key, cidr, from, to), docCount, subAggs));
        }
        return new InternalIpRange(name, buckets, metadata);
    }

    private static void writeFilters(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalFilters f = (InternalFilters) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = f.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            FiltersBucket fb = (FiltersBucket) b;
            out.writeString(fb.getKeyAsString());
            out.writeVLong(fb.getDocCount());
            writeAggregations(out, fb.getAggregations());
        }
        out.writeBoolean(f.isKeyedValue());
    }

    private static InternalAggregation readFilters(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<FiltersBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = in.readString();
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new FiltersBucket(key, docCount, subAggs));
        }
        boolean keyed = in.readBoolean();
        return new InternalFilters(name, buckets, keyed, metadata);
    }

    private static void writeSingleBucket(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalSingleBucketAggregation s = (InternalSingleBucketAggregation) agg;
        out.writeVLong(s.docCount());
        writeAggregations(out, s.aggregations());
    }

    private static InternalSingleBucketAggregation readSingleBucket(StreamInput in, String name, String type, Map<String, Object> metadata)
        throws IOException {
        long docCount = in.readVLong();
        InternalAggregations subAggs = readAggregations(in);
        return new InternalSingleBucketAggregation(name, type, docCount, subAggs, metadata);
    }

    private static void writeRareTerms(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalRareTerms r = (InternalRareTerms) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = r.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            TermsBucket tb = (TermsBucket) b;
            out.writeGenericValue(tb.getKey());
            out.writeVLong(tb.getDocCount());
            out.writeVLong(tb.getDocCountError());
            writeAggregations(out, tb.getAggregations());
        }
        out.writeVLong(r.maxDocCountValue());
    }

    private static InternalAggregation readRareTerms(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<TermsBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object key = in.readGenericValue();
            long docCount = in.readVLong();
            long docCountError = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new TermsBucket(key, docCount, docCountError, subAggs));
        }
        long maxDocCount = in.readVLong();
        return new InternalRareTerms(name, buckets, maxDocCount, metadata);
    }

    private static void writeSignificantTerms(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalSignificantTerms s = (InternalSignificantTerms) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = s.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            SignificantTermsBucket sb = (SignificantTermsBucket) b;
            out.writeString(sb.getKeyAsString());
            out.writeVLong(sb.getDocCount());
            out.writeVLong(sb.getSupersetDf());
            out.writeDouble(sb.getScore());
            writeAggregations(out, sb.getAggregations());
        }
        out.writeVLong(s.subsetSizeValue());
        out.writeVLong(s.supersetSizeValue());
        out.writeString(s.heuristicValue().name());
        out.writeVInt(s.requiredSizeValue());
        out.writeVLong(s.minDocCountValue());
    }

    private static InternalAggregation readSignificantTerms(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<SignificantTermsBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = in.readString();
            long docCount = in.readVLong();
            long supersetDf = in.readVLong();
            double score = in.readDouble();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new SignificantTermsBucket(key, docCount, supersetDf, score, subAggs));
        }
        long subsetSize = in.readVLong();
        long supersetSize = in.readVLong();
        SignificanceHeuristic heuristic = SignificanceHeuristic.valueOf(in.readString());
        int requiredSize = in.readVInt();
        long minDocCount = in.readVLong();
        return new InternalSignificantTerms(name, buckets, subsetSize, supersetSize, heuristic, s -> 0L, requiredSize, minDocCount, metadata);
    }

    private static void writeComposite(StreamOutput out, InternalAggregation agg) throws IOException {
        InternalComposite c = (InternalComposite) agg;
        List<? extends MultiBucketsAggregation.Bucket> buckets = c.getBuckets();
        out.writeVInt(buckets.size());
        for (MultiBucketsAggregation.Bucket b : buckets) {
            CompositeBucket cb = (CompositeBucket) b;
            Map<String, Object> keyAsMap = cb.keyAsMap();
            out.writeVInt(keyAsMap.size());
            for (Map.Entry<String, Object> e : keyAsMap.entrySet()) {
                out.writeString(e.getKey());
                out.writeGenericValue(e.getValue());
            }
            out.writeVLong(cb.getDocCount());
            writeAggregations(out, cb.getAggregations());
        }
        out.writeVInt(c.sizeValue());
        List<Boolean> ascending = c.ascendingValue();
        out.writeVInt(ascending.size());
        for (Boolean a : ascending) {
            out.writeBoolean(a);
        }
    }

    private static InternalAggregation readComposite(StreamInput in, String name, Map<String, Object> metadata) throws IOException {
        int count = in.readVInt();
        List<CompositeBucket> buckets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int keyCount = in.readVInt();
            List<String> sourceNames = new ArrayList<>(keyCount);
            List<Object> key = new ArrayList<>(keyCount);
            for (int k = 0; k < keyCount; k++) {
                sourceNames.add(in.readString());
                key.add(in.readGenericValue());
            }
            long docCount = in.readVLong();
            InternalAggregations subAggs = readAggregations(in);
            buckets.add(new CompositeBucket(sourceNames, key, docCount, subAggs));
        }
        int size = in.readVInt();
        int ascendingCount = in.readVInt();
        List<Boolean> ascending = new ArrayList<>(ascendingCount);
        for (int i = 0; i < ascendingCount; i++) {
            ascending.add(in.readBoolean());
        }
        return new InternalComposite(name, buckets, size, ascending, metadata);
    }

    private static void writeOptionalDouble(StreamOutput out, Double value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeDouble(value);
        }
    }

    private static Double readOptionalDouble(StreamInput in) throws IOException {
        return in.readBoolean() ? in.readDouble() : null;
    }
}
