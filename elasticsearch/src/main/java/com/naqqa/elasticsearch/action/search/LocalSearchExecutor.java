package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.node.search.QueryFactory;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.execution.TopScoreDocCollector;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class LocalSearchExecutor {

    public interface SearcherVisitor<T> {
        T visit(IndexSearcher searcher, DocFetcher fetcher) throws IOException;
    }

    public interface DocFetcher {
        StoredDocCodec.Decoded fetch(int globalDocId) throws IOException;
    }

    private record ShardHit(ShardId shardId, int shardOrdinal, int doc, float score, Object[] values) {
    }

    private LocalSearchExecutor() {
    }

    public static <T> T withSearcher(IndexShard shard, SearcherVisitor<T> visitor) throws IOException {
        EngineSearcher engineSearcher = shard.acquireSearcher();
        try (EngineSearchContext ctx = EngineSearchContext.open(engineSearcher, null)) {
            return visitor.visit(ctx.indexSearcher(), ctx::fetch);
        }
    }

    public static SearchResponse search(Map<ShardId, IndexShard> shards, SearchRequest request) throws IOException {
        Map<ShardId, EngineSearcher> searchers = new java.util.LinkedHashMap<>();
        Map<String, MapperService> mapperServices = new java.util.LinkedHashMap<>();
        for (Map.Entry<ShardId, IndexShard> e : shards.entrySet()) {
            mapperServices.putIfAbsent(e.getKey().index(), e.getValue().mapperService());
        }
        Function<String, QueryFactory.FieldType> fieldTypes = mergedFieldTypes(mapperServices);
        List<SearchResponse.Failure> failures = new ArrayList<>();
        try {
            for (Map.Entry<ShardId, IndexShard> e : shards.entrySet()) {
                try {
                    searchers.put(e.getKey(), e.getValue().acquireSearcher());
                } catch (IOException | RuntimeException ex) {
                    failures.add(new SearchResponse.Failure(e.getKey(), null, ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                }
            }
            SearchResponse response = searchOpen(searchers, request, fieldTypes);
            if (failures.isEmpty()) {
                return response;
            }
            List<SearchResponse.Failure> merged = new ArrayList<>(failures);
            merged.addAll(response.failures());
            SearchResponse.Shards s = response.shards();
            return new SearchResponse(response.hits(), response.totalHits(), response.tookMillis(),
                new SearchResponse.Shards(s.total() + failures.size(), s.successful(), s.failed() + failures.size(), s.skipped()),
                merged, response.timedOut(), response.aggregations());
        } finally {
            for (EngineSearcher searcher : searchers.values()) {
                try {
                    searcher.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static SearchResponse searchOpen(Map<ShardId, EngineSearcher> searchers, SearchRequest request) throws IOException {
        return searchOpen(searchers, request, null);
    }

    private static Function<String, QueryFactory.FieldType> mergedFieldTypes(Map<String, MapperService> mapperServices) {
        Map<String, Function<String, QueryFactory.FieldType>> resolvers = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, MapperService> e : mapperServices.entrySet()) {
            resolvers.put(e.getKey(), AggsPhase.fieldTypesFrom(e.getValue()));
        }
        Map<String, Optional<QueryFactory.FieldType>> cache = new java.util.HashMap<>();
        return field -> cache.computeIfAbsent(field, f -> {
            for (Function<String, QueryFactory.FieldType> resolver : resolvers.values()) {
                QueryFactory.FieldType type = resolver.apply(f);
                if (type != null) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    public static SearchResponse searchOpen(Map<ShardId, EngineSearcher> searchers, SearchRequest request,
                                              Function<String, QueryFactory.FieldType> fieldTypes) throws IOException {
        long startNanos = System.nanoTime();
        int topN = request.from() + request.size();
        List<ShardHit> all = new ArrayList<>();
        List<InternalAggregations> shardAggs = new ArrayList<>();
        List<EngineSearchContext> contexts = new ArrayList<>();
        List<ShardId> order = new ArrayList<>(searchers.keySet());
        long totalValue = 0;
        TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;
        List<SearchResponse.Failure> failures = new ArrayList<>();
        {
            for (int ordinal = 0; ordinal < order.size(); ordinal++) {
                ShardId shardId = order.get(ordinal);
                SegmentOwnership.register(searchers.get(shardId), shardId.index());
                EngineSearchContext ctx = EngineSearchContext.open(searchers.get(shardId), null);
                contexts.add(ctx);
                IndexSearcher searcher = ctx.indexSearcher();
                Map<String, Object> aggsClause = request.aggs();
                TotalHits totalHits;
                if (topN == 0) {
                    if (aggsClause != null) {
                        AggsPhase.Result result = AggsPhase.execute(searcher, request.query(), null, aggsClause,
                            new MultiBucketConsumer(request.maxBuckets()), fieldTypes);
                        shardAggs.add(result.aggregations);
                        totalHits = new TotalHits(result.matchedDocCount, TotalHits.Relation.EQUAL_TO);
                    } else {
                        totalHits = new TotalHits(searcher.count(request.query()), TotalHits.Relation.EQUAL_TO);
                    }
                } else if (request.sort() != null) {
                    ShardFieldCollector collector = new ShardFieldCollector(request.sort(), topN);
                    if (aggsClause != null) {
                        AggsPhase.Result result = AggsPhase.execute(searcher, request.query(), collector, aggsClause,
                            new MultiBucketConsumer(request.maxBuckets()), fieldTypes);
                        shardAggs.add(result.aggregations);
                    } else {
                        searcher.search(request.query(), collector);
                    }
                    totalHits = collector.totalHits();
                    for (ShardFieldCollector.Hit hit : collector.results()) {
                        all.add(new ShardHit(shardId, ordinal, hit.doc(), hit.score(), hit.values()));
                    }
                } else {
                    TopScoreDocCollector collector = TopScoreDocCollector.create(topN);
                    if (aggsClause != null) {
                        AggsPhase.Result result = AggsPhase.execute(searcher, request.query(), collector, aggsClause,
                            new MultiBucketConsumer(request.maxBuckets()), fieldTypes);
                        shardAggs.add(result.aggregations);
                    } else {
                        searcher.search(request.query(), collector);
                    }
                    TopDocs topDocs = collector.topDocs();
                    totalHits = topDocs.totalHits();
                    for (ScoreDoc sd : topDocs.scoreDocs()) {
                        all.add(new ShardHit(shardId, ordinal, sd.doc, sd.score, null));
                    }
                }
                totalValue += totalHits.value();
                if (totalHits.relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
                    relation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
                }
            }
            Sort sort = request.sort();
            all.sort((a, b) -> compare(a, b, sort));
            List<SearchResponse.Hit> hits = new ArrayList<>();
            for (int i = request.from(); i < all.size() && hits.size() < request.size(); i++) {
                ShardHit hit = all.get(i);
                StoredDocCodec.Decoded decoded = contexts.get(hit.shardOrdinal()).fetch(hit.doc());
                hits.add(new SearchResponse.Hit(hit.shardId().index(), decoded == null ? null : decoded.id(), hit.score(),
                    decoded == null ? null : decoded.source(), hit.values()));
            }
            Map<String, Object> aggregations = null;
            if (request.aggs() != null) {
                InternalAggregations reduced = InternalAggregations.reduceAll(shardAggs,
                    ReduceContext.forFinalReduction(new MultiBucketConsumer(request.maxBuckets())));
                aggregations = reduced.toMap();
            }
            int total = order.size();
            SearchResponse.Shards shardStats = new SearchResponse.Shards(total, total - failures.size(), failures.size(), 0);
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            return new SearchResponse(hits, new TotalHits(totalValue, relation), took, shardStats, failures, false, aggregations);
        }
    }

    private static int compare(ShardHit a, ShardHit b, Sort sort) {
        if (sort != null) {
            SortField[] fields = sort.fields();
            for (int i = 0; i < fields.length; i++) {
                int cmp = compareField(fields[i], a, b, i);
                if (cmp != 0) {
                    return cmp;
                }
            }
        } else {
            int cmp = -Float.compare(a.score(), b.score());
            if (cmp != 0) {
                return cmp;
            }
        }
        int cmp = Integer.compare(a.shardOrdinal(), b.shardOrdinal());
        return cmp != 0 ? cmp : Integer.compare(a.doc(), b.doc());
    }

    private static int compareField(SortField f, ShardHit a, ShardHit b, int idx) {
        int cmp = switch (f.type()) {
            case DOC -> {
                int s = Integer.compare(a.shardOrdinal(), b.shardOrdinal());
                yield s != 0 ? s : Integer.compare(a.doc(), b.doc());
            }
            case SCORE -> -Float.compare(a.score(), b.score());
            case LONG -> Comparator.<Long>nullsLast(Comparator.naturalOrder()).compare((Long) a.values()[idx], (Long) b.values()[idx]);
            case DOUBLE -> Comparator.<Double>nullsLast(Comparator.naturalOrder()).compare((Double) a.values()[idx], (Double) b.values()[idx]);
            case STRING -> Arrays.compareUnsigned((byte[]) a.values()[idx], (byte[]) b.values()[idx]);
        };
        return f.reverse() ? -cmp : cmp;
    }
}
