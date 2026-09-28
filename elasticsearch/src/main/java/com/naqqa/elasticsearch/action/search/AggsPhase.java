package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.common.bytes.BytesRef;
import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.index.mapper.FieldMapper;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.mapper.NumberFieldMapper;
import com.naqqa.elasticsearch.node.search.QueryFactory;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.AggregatorFactories;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.DocValuesAdapters;
import com.naqqa.elasticsearch.search.aggs.support.DoubleValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.GeoPointValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.LongValuesSource;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.SortedSetValues;
import com.naqqa.elasticsearch.search.aggs.support.ValuesLookup;
import com.naqqa.elasticsearch.search.execution.Collector;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafCollector;
import com.naqqa.elasticsearch.search.execution.LeafReader;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class AggsPhase {

    private static final LongValuesSource EMPTY_LONG = new LongValuesSource() {
        @Override
        public boolean advanceExact(int doc) {
            return false;
        }

        @Override
        public int docValueCount() {
            return 0;
        }

        @Override
        public long nextValue() {
            throw new IllegalStateException("no values for current doc");
        }
    };

    private static final SortedSetValues EMPTY_SORTED_SET = new SortedSetValues() {
        @Override
        public boolean advanceExact(int doc) {
            return false;
        }

        @Override
        public int docValueCount() {
            return 0;
        }

        @Override
        public long nextOrd() {
            throw new IllegalStateException("no values for current doc");
        }

        @Override
        public BytesRef lookupOrd(long ord) {
            throw new IllegalStateException("no values in this field");
        }

        @Override
        public long getValueCount() {
            return 0;
        }
    };

    private static final GeoPointValuesSource EMPTY_GEO = new GeoPointValuesSource() {
        @Override
        public boolean advanceExact(int doc) {
            return false;
        }

        @Override
        public int docValueCount() {
            return 0;
        }

        @Override
        public GeoPoint nextValue() {
            throw new IllegalStateException("no values for current doc");
        }
    };

    private AggsPhase() {
    }

    static final class Result {
        final InternalAggregations aggregations;
        final long matchedDocCount;

        Result(InternalAggregations aggregations, long matchedDocCount) {
            this.aggregations = aggregations;
            this.matchedDocCount = matchedDocCount;
        }
    }

    public static final class ShardAggregations {
        private final AggsCollector collector;

        private ShardAggregations(AggsCollector collector) {
            this.collector = collector;
        }

        public Collector collector() {
            return collector;
        }

        public InternalAggregations finish() {
            return collector.finish();
        }

        public long matchedDocCount() {
            return collector.matchedDocCount();
        }
    }

    public static ShardAggregations create(Map<String, Object> aggsClause, MultiBucketConsumer bucketConsumer) {
        return create(aggsClause, bucketConsumer, null);
    }

    public static ShardAggregations create(Map<String, Object> aggsClause, MultiBucketConsumer bucketConsumer,
                                             Function<String, QueryFactory.FieldType> fieldTypes) {
        return new ShardAggregations(new AggsCollector(aggsClause, bucketConsumer, fieldTypes));
    }

    static Result execute(IndexSearcher searcher, Query query, Collector hitCollector,
                           Map<String, Object> aggsClause, MultiBucketConsumer bucketConsumer) throws IOException {
        return execute(searcher, query, hitCollector, aggsClause, bucketConsumer, null);
    }

    static Result execute(IndexSearcher searcher, Query query, Collector hitCollector,
                           Map<String, Object> aggsClause, MultiBucketConsumer bucketConsumer,
                           Function<String, QueryFactory.FieldType> fieldTypes) throws IOException {
        AggsCollector aggsCollector = new AggsCollector(aggsClause, bucketConsumer, fieldTypes);
        Collector combined = hitCollector == null ? aggsCollector : new CombinedCollector(hitCollector, aggsCollector);
        searcher.search(query, combined);
        return new Result(aggsCollector.finish(), aggsCollector.matchedDocCount());
    }

    public static Function<String, QueryFactory.FieldType> fieldTypesFrom(MapperService mapperService) {
        if (mapperService == null) {
            return field -> null;
        }
        Map<String, Optional<QueryFactory.FieldType>> cache = new ConcurrentHashMap<>();
        return field -> cache.computeIfAbsent(field, f -> Optional.ofNullable(resolveFieldType(mapperService, f))).orElse(null);
    }

    private static QueryFactory.FieldType resolveFieldType(MapperService mapperService, String field) {
        if (mapperService.documentMapper() == null) {
            return null;
        }
        FieldMapper mapper = mapperService.documentMapper().mapping().fieldMapper(field);
        if (mapper == null) {
            return null;
        }
        String type = mapper.typeName();
        double scaling = 1.0;
        if (mapper instanceof NumberFieldMapper numberFieldMapper
            && numberFieldMapper.numberType() == NumberFieldMapper.NumberType.SCALED_FLOAT) {
            scaling = numberFieldMapper.scalingFactor();
        }
        return new QueryFactory.FieldType(type, null, scaling);
    }

    private static final class CombinedCollector implements Collector {
        private final Collector hits;
        private final Collector aggs;

        CombinedCollector(Collector hits, Collector aggs) {
            this.hits = hits;
            this.aggs = aggs;
        }

        @Override
        public ScoreMode scoreMode() {
            boolean needsScores = hits.scoreMode().needsScores() || aggs.scoreMode().needsScores();
            return needsScores ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
            LeafCollector hitsLeaf = hits.getLeafCollector(context);
            LeafCollector aggsLeaf = aggs.getLeafCollector(context);
            return new LeafCollector() {
                @Override
                public void setScorer(Scorer scorer) throws IOException {
                    hitsLeaf.setScorer(scorer);
                    aggsLeaf.setScorer(scorer);
                }

                @Override
                public void collect(int doc) throws IOException {
                    hitsLeaf.collect(doc);
                    aggsLeaf.collect(doc);
                }
            };
        }
    }

    private static final class AggsCollector implements Collector {
        private final Map<String, Object> aggsClause;
        private final MultiBucketConsumer bucketConsumer;
        private final Function<String, QueryFactory.FieldType> fieldTypes;
        private final List<Aggregator> perLeafAggregators = new ArrayList<>();
        private long matchedDocCount;

        AggsCollector(Map<String, Object> aggsClause, MultiBucketConsumer bucketConsumer,
                      Function<String, QueryFactory.FieldType> fieldTypes) {
            this.aggsClause = aggsClause;
            this.bucketConsumer = bucketConsumer;
            this.fieldTypes = fieldTypes == null ? field -> null : fieldTypes;
        }

        @Override
        public ScoreMode scoreMode() {
            return ScoreMode.COMPLETE_NO_SCORES;
        }

        @Override
        public LeafCollector getLeafCollector(LeafReaderContext context) {
            ValuesLookup lookup = new LeafValuesLookup(context.reader(), fieldTypes);
            Aggregator top = AggregatorFactories.createTopLevel(aggsClause, lookup, bucketConsumer);
            perLeafAggregators.add(top);
            return new LeafCollector() {
                @Override
                public void setScorer(Scorer scorer) {
                }

                @Override
                public void collect(int doc) {
                    matchedDocCount++;
                    top.collect(doc, 0);
                }
            };
        }

        long matchedDocCount() {
            return matchedDocCount;
        }

        InternalAggregations finish() {
            List<InternalAggregations> partials = new ArrayList<>(perLeafAggregators.size());
            for (Aggregator top : perLeafAggregators) {
                top.postCollection();
                partials.add((InternalAggregations) top.buildAggregation(0));
            }
            return InternalAggregations.reduceAll(partials, ReduceContext.forPartialReduction());
        }
    }

    private static final class LeafValuesLookup implements ValuesLookup {
        private static final java.util.Set<String> FLOATING_POINT_TYPES =
            java.util.Set.of("double", "float", "half_float", "scaled_float");

        private final LeafReader reader;
        private final Function<String, QueryFactory.FieldType> fieldTypes;

        LeafValuesLookup(LeafReader reader, Function<String, QueryFactory.FieldType> fieldTypes) {
            this.reader = reader;
            this.fieldTypes = fieldTypes == null ? field -> null : fieldTypes;
        }

        private QueryFactory.FieldType fieldType(String field) {
            return fieldTypes.apply(field);
        }

        @Override
        public LongValuesSource longValues(String field) {
            try {
                NumericDocValuesReader numeric = reader.numericDocValues(field);
                if (numeric != null) {
                    return DocValuesAdapters.of(numeric);
                }
                SortedNumericDocValuesReader sorted = reader.sortedNumericDocValues(field);
                if (sorted != null) {
                    return DocValuesAdapters.of(sorted);
                }
                return EMPTY_LONG;
            } catch (IOException e) {
                throw new RuntimeException("failed to read numeric doc values for field [" + field + "]", e);
            }
        }

        @Override
        public boolean isFloatingPoint(String field) {
            QueryFactory.FieldType type = fieldType(field);
            return type != null && FLOATING_POINT_TYPES.contains(type.type());
        }

        @Override
        public DoubleValuesSource doubleValues(String field) {
            QueryFactory.FieldType type = fieldType(field);
            String t = type == null ? null : type.type();
            LongValuesSource raw = longValues(field);
            if (t == null) {
                return DoubleValuesSource.of(raw, false);
            }
            return switch (t) {
                case "double" -> DoubleValuesSource.of(raw, true);
                case "float", "half_float" -> DoubleValuesSource.sortableFloat(raw);
                case "scaled_float" -> DoubleValuesSource.scaled(raw, type.scalingFactor());
                default -> DoubleValuesSource.of(raw, false);
            };
        }

        @Override
        public SortedSetValues bytesValues(String field) {
            try {
                SortedSetDocValuesReader sortedSet = reader.sortedSetDocValues(field);
                if (sortedSet != null) {
                    return DocValuesAdapters.of(sortedSet, reader.numTerms(field));
                }
                SortedDocValuesReader sorted = reader.sortedDocValues(field);
                if (sorted != null) {
                    return DocValuesAdapters.of(sorted);
                }
                return EMPTY_SORTED_SET;
            } catch (IOException e) {
                throw new RuntimeException("failed to read sorted doc values for field [" + field + "]", e);
            }
        }

        @Override
        public GeoPointValuesSource geoPointValues(String field) {
            QueryFactory.FieldType type = fieldType(field);
            if (type == null || !"geo_point".equals(type.type())) {
                return EMPTY_GEO;
            }
            try {
                NumericDocValuesReader numeric = reader.numericDocValues(field);
                if (numeric == null) {
                    return EMPTY_GEO;
                }
                return DocValuesAdapters.geoPointValues(numeric);
            } catch (IOException e) {
                throw new RuntimeException("failed to read geo point doc values for field [" + field + "]", e);
            }
        }
    }
}
