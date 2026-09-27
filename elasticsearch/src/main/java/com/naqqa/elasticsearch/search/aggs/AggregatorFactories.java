package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.composite.CompositeAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.AdjacencyMatrixAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.FilterAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.FiltersAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.GlobalAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.filter.MissingAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.geo.GeoDistanceAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.geo.GeohashGridAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.geo.GeotileGridAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.AutoDateHistogramAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.DateHistogramAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.HistogramAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.histogram.VariableWidthHistogramAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.nested.ChildrenAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.nested.NestedAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.nested.ParentAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.nested.ReverseNestedAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.range.DateRangeAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.range.IpRangeAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.range.RangeAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.sampler.DiversifiedSamplerAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.sampler.RandomSamplerAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.sampler.SamplerAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.MultiTermsAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.RareTermsAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.SignificantTermsAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.SignificantTextAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.AvgAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.BoxplotAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.CardinalityAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.ExtendedStatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.GeoBoundsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.GeoCentroidAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.MatrixStatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.MaxAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.MedianAbsoluteDeviationAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.MinAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentileRanksAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.PercentilesAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.RateAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.ScriptedMetricAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.StatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.StringStatsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.SumAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.TTestAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.TopHitsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.TopMetricsAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.ValueCountAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.WeightedAvgAggregator;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;
import com.naqqa.elasticsearch.search.aggs.support.ValuesLookup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AggregatorFactories {

    @FunctionalInterface
    public interface AggParser {
        Aggregator parse(AggParseContext ctx);
    }

    private static final Map<String, AggParser> REGISTRY = new HashMap<>();

    static {
        register("avg", AvgAggregator::parse);
        register("sum", SumAggregator::parse);
        register("min", MinAggregator::parse);
        register("max", MaxAggregator::parse);
        register("value_count", ValueCountAggregator::parse);
        register("stats", StatsAggregator::parse);
        register("extended_stats", ExtendedStatsAggregator::parse);
        register("weighted_avg", WeightedAvgAggregator::parse);
        register("median_absolute_deviation", MedianAbsoluteDeviationAggregator::parse);
        register("cardinality", CardinalityAggregator::parse);
        register("percentiles", PercentilesAggregator::parse);
        register("percentile_ranks", PercentileRanksAggregator::parse);
        register("top_hits", TopHitsAggregator::parse);
        register("top_metrics", TopMetricsAggregator::parse);
        register("geo_bounds", GeoBoundsAggregator::parse);
        register("geo_centroid", GeoCentroidAggregator::parse);
        register("scripted_metric", ScriptedMetricAggregator::parse);
        register("string_stats", StringStatsAggregator::parse);
        register("boxplot", BoxplotAggregator::parse);
        register("rate", RateAggregator::parse);
        register("t_test", TTestAggregator::parse);
        register("matrix_stats", MatrixStatsAggregator::parse);

        register("terms", TermsAggregator::parse);
        register("rare_terms", RareTermsAggregator::parse);
        register("multi_terms", MultiTermsAggregator::parse);
        register("significant_terms", SignificantTermsAggregator::parse);
        register("significant_text", SignificantTextAggregator::parse);
        register("histogram", HistogramAggregator::parse);
        register("date_histogram", DateHistogramAggregator::parse);
        register("auto_date_histogram", AutoDateHistogramAggregator::parse);
        register("variable_width_histogram", VariableWidthHistogramAggregator::parse);
        register("range", RangeAggregator::parse);
        register("date_range", DateRangeAggregator::parse);
        register("ip_range", IpRangeAggregator::parse);
        register("geo_distance", GeoDistanceAggregator::parse);
        register("geohash_grid", GeohashGridAggregator::parse);
        register("geotile_grid", GeotileGridAggregator::parse);
        register("filter", FilterAggregator::parse);
        register("filters", FiltersAggregator::parse);
        register("global", GlobalAggregator::parse);
        register("missing", MissingAggregator::parse);
        register("nested", NestedAggregator::parse);
        register("reverse_nested", ReverseNestedAggregator::parse);
        register("children", ChildrenAggregator::parse);
        register("parent", ParentAggregator::parse);
        register("sampler", SamplerAggregator::parse);
        register("diversified_sampler", DiversifiedSamplerAggregator::parse);
        register("random_sampler", RandomSamplerAggregator::parse);
        register("adjacency_matrix", AdjacencyMatrixAggregator::parse);
        register("composite", CompositeAggregator::parse);
    }

    private AggregatorFactories() {
    }

    public static void register(String type, AggParser parser) {
        REGISTRY.put(type, parser);
    }

    public static Aggregator createTopLevel(Map<String, Object> aggsClause, ValuesLookup lookup, MultiBucketConsumer bucketConsumer) {
        return new TopLevelAggregator(build(aggsClause, lookup, bucketConsumer));
    }

    public static Aggregator[] build(Map<String, Object> aggsClause, ValuesLookup lookup, MultiBucketConsumer bucketConsumer) {
        if (aggsClause == null || aggsClause.isEmpty()) {
            return new Aggregator[0];
        }
        List<Aggregator> list = new ArrayList<>();
        for (Map.Entry<String, Object> e : aggsClause.entrySet()) {
            list.add(parseOne(e.getKey(), ParamsHelper.asMap(e.getValue()), lookup, bucketConsumer));
        }
        return list.toArray(new Aggregator[0]);
    }

    private static Aggregator parseOne(String name, Map<String, Object> clause, ValuesLookup lookup, MultiBucketConsumer bucketConsumer) {
        String typeKey = null;
        Map<String, Object> typeParams = null;
        Map<String, Object> subAggsClause = null;
        Map<String, Object> meta = null;
        for (Map.Entry<String, Object> e : clause.entrySet()) {
            switch (e.getKey()) {
                case "aggs", "aggregations" -> subAggsClause = ParamsHelper.asMap(e.getValue());
                case "meta" -> meta = ParamsHelper.asMap(e.getValue());
                default -> {
                    typeKey = e.getKey();
                    typeParams = ParamsHelper.asMap(e.getValue());
                }
            }
        }
        if (typeKey == null) {
            throw new AggregationExecutionException("aggregation [" + name + "] has no type");
        }
        AggParser parser = REGISTRY.get(typeKey);
        if (parser == null) {
            throw new AggregationExecutionException("unknown aggregation type [" + typeKey + "]");
        }
        Aggregator[] subAggregators = build(subAggsClause, lookup, bucketConsumer);
        AggParseContext ctx = new AggParseContext(name, typeParams, subAggregators, lookup, bucketConsumer, meta);
        return parser.parse(ctx);
    }

    private static final class TopLevelAggregator extends Aggregator {

        TopLevelAggregator(Aggregator[] children) {
            super("_top", children);
        }

        @Override
        public void collect(int doc, long bucketOrd) {
            for (Aggregator sub : subAggregators) {
                sub.collect(doc, bucketOrd);
            }
        }

        @Override
        public InternalAggregation buildAggregation(long bucketOrd) {
            InternalAggregation[] result = new InternalAggregation[subAggregators.length];
            for (int i = 0; i < subAggregators.length; i++) {
                result[i] = subAggregators[i].buildAggregation(bucketOrd);
            }
            return InternalAggregations.from(result);
        }
    }
}
