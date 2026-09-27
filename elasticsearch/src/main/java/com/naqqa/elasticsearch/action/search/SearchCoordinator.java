package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.TooManyBucketsException;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.similarity.CollectionStatistics;
import com.naqqa.elasticsearch.search.similarity.TermStatistics;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.ConnectionProfile;
import com.naqqa.elasticsearch.transport.DiscoveryNode;
import com.naqqa.elasticsearch.transport.ReceiveTimeoutTransportException;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;
import com.naqqa.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SearchCoordinator {

    private final TransportService transportService;
    private final Map<String, DiscoveryNode> nodes;
    private final ShardCopySelector roundRobinSelector = new ShardCopySelector.RoundRobin();
    private final ShardCopySelector adaptiveSelector = new ShardCopySelector.AdaptiveReplica();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public SearchCoordinator(TransportService transportService, Map<String, DiscoveryNode> nodes) {
        this.transportService = transportService;
        this.nodes = nodes;
    }

    public SearchResponse search(RoutingTable routingTable, SearchRequest request) {
        long startNanos = System.nanoTime();
        IndexRoutingTable indexRouting = routingTable.index(request.index());
        if (indexRouting == null) {
            throw new IllegalArgumentException("no such index [" + request.index() + "]");
        }

        List<ShardId> shardIds = new ArrayList<>();
        for (IndexShardRoutingTable table : new TreeMap<>(indexRouting.getShards()).values()) {
            shardIds.add(table.getShardId());
        }

        ShardCopySelector selector = request.useAdaptiveReplicaSelection() ? adaptiveSelector : roundRobinSelector;
        Map<ShardId, ShardRouting> chosenCopy = new LinkedHashMap<>();
        List<SearchResponse.Failure> failures = new ArrayList<>();
        for (ShardId shardId : shardIds) {
            IndexShardRoutingTable table = indexRouting.shard(shardId.id());
            List<ShardRouting> candidates = new ArrayList<>();
            for (ShardRouting sr : table.getShards()) {
                if (sr.started()) {
                    candidates.add(sr);
                }
            }
            String localNodeId = transportService.localNode() == null ? null : transportService.localNode().id();
            ShardRouting chosen = PreferenceResolver.resolve(request.preference(), localNodeId, shardId, candidates, selector);
            if (chosen == null) {
                failures.add(new SearchResponse.Failure(shardId, null, "no active shard copies available"));
            } else {
                chosenCopy.put(shardId, chosen);
            }
        }

        Set<ShardId> skipped = new java.util.LinkedHashSet<>();
        if (request.canMatchRange() != null && shardIds.size() > request.preFilterShardSize()) {
            SearchRequest.CanMatchRange range = request.canMatchRange();
            List<CompletableFuture<ShardSearchService.CanMatchResponse>> futures = new ArrayList<>();
            List<ShardId> ordered = new ArrayList<>(chosenCopy.keySet());
            for (ShardId shardId : ordered) {
                ShardRouting copy = chosenCopy.get(shardId);
                ShardSearchService.CanMatchRequest req = new ShardSearchService.CanMatchRequest(shardId, range.field(), range.lower(), range.upper());
                futures.add(sendRequest(copy, ShardSearchService.ACTION_CAN_MATCH, req, request.timeoutMillis()));
            }
            for (int i = 0; i < ordered.size(); i++) {
                try {
                    ShardSearchService.CanMatchResponse resp = futures.get(i).get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
                    if (!resp.canMatch()) {
                        skipped.add(ordered.get(i));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        List<ShardId> activeShardIds = new ArrayList<>();
        for (ShardId shardId : chosenCopy.keySet()) {
            if (!skipped.contains(shardId)) {
                activeShardIds.add(shardId);
            }
        }

        Map<String, CollectionStatistics> dfsCollStats = null;
        Map<Term, TermStatistics> dfsTermStats = null;
        if (request.searchType() == SearchType.DFS_QUERY_THEN_FETCH) {
            Set<Term> terms = QueryCodec.extractTerms(request.query());
            Set<String> fields = new java.util.LinkedHashSet<>();
            for (Term term : terms) {
                fields.add(term.field());
            }
            List<CompletableFuture<ShardSearchService.DfsResponse>> futures = new ArrayList<>();
            for (ShardId shardId : activeShardIds) {
                ShardRouting copy = chosenCopy.get(shardId);
                ShardSearchService.DfsRequest req = new ShardSearchService.DfsRequest(shardId, new ArrayList<>(fields), new ArrayList<>(terms));
                futures.add(sendRequest(copy, ShardSearchService.ACTION_DFS, req, request.timeoutMillis()));
            }
            long maxDoc = 0, docCount = 0, sumDocFreq = 0, sumTotalTermFreq = 0;
            Map<String, long[]> collAgg = new LinkedHashMap<>();
            Map<Term, long[]> termAgg = new LinkedHashMap<>();
            for (int i = 0; i < activeShardIds.size(); i++) {
                try {
                    ShardSearchService.DfsResponse resp = futures.get(i).get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
                    for (Map.Entry<String, CollectionStatistics> e : resp.collectionStats().entrySet()) {
                        CollectionStatistics cs = e.getValue();
                        long[] agg = collAgg.computeIfAbsent(e.getKey(), k -> new long[4]);
                        agg[0] += cs.maxDoc();
                        agg[1] += cs.docCount();
                        agg[2] += cs.sumDocFreq();
                        agg[3] += cs.sumTotalTermFreq();
                    }
                    for (Map.Entry<Term, TermStatistics> e : resp.termStats().entrySet()) {
                        TermStatistics ts = e.getValue();
                        long[] agg = termAgg.computeIfAbsent(e.getKey(), k -> new long[2]);
                        agg[0] += ts.docFreq();
                        agg[1] += ts.totalTermFreq();
                    }
                } catch (Exception ignored) {
                }
            }
            dfsCollStats = new LinkedHashMap<>();
            for (Map.Entry<String, long[]> e : collAgg.entrySet()) {
                long[] a = e.getValue();
                dfsCollStats.put(e.getKey(), new CollectionStatistics(e.getKey(), a[0], a[1], a[2], a[3]));
            }
            dfsTermStats = new LinkedHashMap<>();
            for (Map.Entry<Term, long[]> e : termAgg.entrySet()) {
                long[] a = e.getValue();
                dfsTermStats.put(e.getKey(), new TermStatistics(e.getKey().bytes(), a[0], a[1]));
            }
        }

        Map<ShardId, CompletableFuture<ShardSearchService.ShardQueryResponse>> queryFutures = new LinkedHashMap<>();
        for (ShardId shardId : activeShardIds) {
            ShardRouting copy = chosenCopy.get(shardId);
            ShardSearchService.ShardQueryRequest req = new ShardSearchService.ShardQueryRequest(
                shardId, request.query(), request.sort(), request.from(), request.size(), dfsCollStats, dfsTermStats,
                request.aggs(), request.maxBuckets());
            queryFutures.put(shardId, sendRequest(copy, ShardSearchService.ACTION_QUERY, req, request.timeoutMillis()));
        }

        Map<ShardId, ShardSearchService.ShardQueryResponse> queryResults = new LinkedHashMap<>();
        for (Map.Entry<ShardId, CompletableFuture<ShardSearchService.ShardQueryResponse>> e : queryFutures.entrySet()) {
            ShardRouting copy = chosenCopy.get(e.getKey());
            try {
                long callStart = System.nanoTime();
                ShardSearchService.ShardQueryResponse resp = e.getValue().get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
                queryResults.put(e.getKey(), resp);
                selector.onResponse(copy, resp.tookNanos());
            } catch (Exception ex) {
                selector.onFailure(copy);
                failures.add(new SearchResponse.Failure(e.getKey(), copy.currentNodeId(), describe(ex)));
            }
        }

        if (!failures.isEmpty() && !request.allowPartialSearchResults()) {
            for (Map.Entry<ShardId, ShardSearchService.ShardQueryResponse> e : queryResults.entrySet()) {
                clearContext(chosenCopy.get(e.getKey()), e.getKey(), e.getValue().contextId(), request.timeoutMillis());
            }
            throw new SearchPhaseExecutionException("query", failures);
        }

        MergedHits merged = mergeHits(queryResults, request);

        Map<ShardId, List<Integer>> docsByShardForFetch = new LinkedHashMap<>();
        for (MergedHit hit : merged.page) {
            docsByShardForFetch.computeIfAbsent(hit.shardId, k -> new ArrayList<>()).add(hit.doc);
        }

        Map<ShardId, CompletableFuture<ShardSearchService.ShardFetchResponse>> fetchFutures = new LinkedHashMap<>();
        for (Map.Entry<ShardId, ShardSearchService.ShardQueryResponse> e : queryResults.entrySet()) {
            ShardId shardId = e.getKey();
            List<Integer> docIdList = docsByShardForFetch.getOrDefault(shardId, List.of());
            int[] docIds = new int[docIdList.size()];
            for (int i = 0; i < docIds.length; i++) {
                docIds[i] = docIdList.get(i);
            }
            ShardSearchService.ShardFetchRequest req = new ShardSearchService.ShardFetchRequest(shardId, e.getValue().contextId(), docIds);
            fetchFutures.put(shardId, sendRequest(chosenCopy.get(shardId), ShardSearchService.ACTION_FETCH, req, request.timeoutMillis()));
        }

        Map<ShardId, Map<Integer, ShardSearchService.ShardFetchResponse.FetchedDoc>> fetchedByShard = new LinkedHashMap<>();
        for (Map.Entry<ShardId, CompletableFuture<ShardSearchService.ShardFetchResponse>> e : fetchFutures.entrySet()) {
            try {
                ShardSearchService.ShardFetchResponse resp = e.getValue().get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
                List<Integer> docIdList = docsByShardForFetch.getOrDefault(e.getKey(), List.of());
                Map<Integer, ShardSearchService.ShardFetchResponse.FetchedDoc> byDoc = new LinkedHashMap<>();
                for (int i = 0; i < docIdList.size() && i < resp.docs().size(); i++) {
                    byDoc.put(docIdList.get(i), resp.docs().get(i));
                }
                fetchedByShard.put(e.getKey(), byDoc);
            } catch (Exception ex) {
                failures.add(new SearchResponse.Failure(e.getKey(), chosenCopy.get(e.getKey()).currentNodeId(), describe(ex)));
            }
        }

        List<SearchResponse.Hit> hits = new ArrayList<>();
        for (MergedHit hit : merged.page) {
            Map<Integer, ShardSearchService.ShardFetchResponse.FetchedDoc> byDoc = fetchedByShard.get(hit.shardId);
            ShardSearchService.ShardFetchResponse.FetchedDoc fetched = byDoc == null ? null : byDoc.get(hit.doc);
            String id = fetched == null ? null : fetched.id();
            byte[] source = fetched == null ? null : fetched.source();
            hits.add(new SearchResponse.Hit(request.index(), id, hit.score, source, hit.sortValues));
        }

        Map<String, Object> aggregationsResult = null;
        if (request.aggs() != null) {
            List<InternalAggregations> shardAggs = new ArrayList<>();
            for (ShardSearchService.ShardQueryResponse resp : queryResults.values()) {
                if (resp.aggregations() != null) {
                    shardAggs.add(resp.aggregations());
                }
            }
            try {
                InternalAggregations finalAggs = InternalAggregations.reduceAll(
                    shardAggs, ReduceContext.forFinalReduction(new MultiBucketConsumer(request.maxBuckets())));
                aggregationsResult = finalAggs.toMap();
            } catch (TooManyBucketsException e) {
                failures.add(new SearchResponse.Failure(null, null, describe(e)));
                throw new SearchPhaseExecutionException("aggregation_reduce", failures);
            }
        }

        int successful = queryResults.size();
        int failed = failures.size();
        int total = shardIds.size();
        SearchResponse.Shards shardStats = new SearchResponse.Shards(total, successful, failed, skipped.size());
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return new SearchResponse(hits, merged.totalHits, tookMillis, shardStats, failures, false, aggregationsResult);
    }

    private void clearContext(ShardRouting copy, ShardId shardId, long contextId, long timeoutMillis) {
        try {
            sendRequest(copy, ShardSearchService.ACTION_FETCH,
                new ShardSearchService.ShardFetchRequest(shardId, contextId, new int[0]), timeoutMillis)
                .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static final class MergedHit {
        final ShardId shardId;
        final int doc;
        final float score;
        final Object[] sortValues;

        MergedHit(ShardId shardId, int doc, float score, Object[] sortValues) {
            this.shardId = shardId;
            this.doc = doc;
            this.score = score;
            this.sortValues = sortValues;
        }
    }

    private static final class MergedHits {
        final List<MergedHit> page;
        final TotalHits totalHits;

        MergedHits(List<MergedHit> page, TotalHits totalHits) {
            this.page = page;
            this.totalHits = totalHits;
        }
    }

    private MergedHits mergeHits(Map<ShardId, ShardSearchService.ShardQueryResponse> queryResults, SearchRequest request) {
        Sort sort = request.sort();
        int windowSize = request.from() + request.size();
        PriorityQueue<MergedHit> pq = new PriorityQueue<>(Math.max(windowSize, 1), false) {
            @Override
            protected boolean lessThan(MergedHit a, MergedHit b) {
                return compare(a, b, sort) > 0;
            }
        };
        long totalValue = 0;
        TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;
        for (Map.Entry<ShardId, ShardSearchService.ShardQueryResponse> e : queryResults.entrySet()) {
            ShardSearchService.ShardQueryResponse resp = e.getValue();
            totalValue += resp.totalHits().value();
            if (resp.totalHits().relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
                relation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
            }
            for (ShardSearchService.ShardQueryResponse.Hit hit : resp.hits()) {
                if (windowSize > 0) {
                    pq.insertWithOverflow(new MergedHit(e.getKey(), hit.doc(), hit.score(), hit.values()));
                }
            }
        }
        int size = pq.size();
        MergedHit[] arr = pq.drainToArrayHighestFirst(new MergedHit[size]);
        List<MergedHit> page = new ArrayList<>();
        for (int i = request.from(); i < arr.length && page.size() < request.size(); i++) {
            page.add(arr[i]);
        }
        return new MergedHits(page, new TotalHits(totalValue, relation));
    }

    private static int compare(MergedHit a, MergedHit b, Sort sort) {
        if (sort != null) {
            SortField[] fields = sort.fields();
            for (int i = 0; i < fields.length; i++) {
                int cmp = compareField(fields[i], a, b, i);
                if (cmp != 0) {
                    return cmp;
                }
            }
        } else {
            int cmp = -Float.compare(a.score, b.score);
            if (cmp != 0) {
                return cmp;
            }
        }
        int cmp = Integer.compare(a.shardId.id(), b.shardId.id());
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(a.doc, b.doc);
    }

    @SuppressWarnings("unchecked")
    private static int compareField(SortField f, MergedHit a, MergedHit b, int idx) {
        int cmp = switch (f.type()) {
            case DOC -> {
                int shardCmp = Integer.compare(a.shardId.id(), b.shardId.id());
                yield shardCmp != 0 ? shardCmp : Integer.compare(a.doc, b.doc);
            }
            case SCORE -> -Float.compare(a.score, b.score);
            case LONG -> Long.compare((Long) a.sortValues[idx], (Long) b.sortValues[idx]);
            case DOUBLE -> Double.compare((Double) a.sortValues[idx], (Double) b.sortValues[idx]);
            case STRING -> Arrays.compareUnsigned((byte[]) a.sortValues[idx], (byte[]) b.sortValues[idx]);
        };
        return f.reverse() ? -cmp : cmp;
    }

    private <T extends com.naqqa.elasticsearch.transport.TransportResponse> CompletableFuture<T> sendRequest(
        ShardRouting copy, String action, com.naqqa.elasticsearch.transport.TransportRequest request, long timeoutMillis) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Connection connection = connectionFor(copy.currentNodeId());
            transportService.sendRequest(connection, action, request,
                TransportRequestOptions.of().withTimeout(timeoutMillis),
                new TransportResponseHandler<T>() {
                    @Override
                    public void handleResponse(T response) {
                        future.complete(response);
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        future.completeExceptionally(exp);
                    }

                    @SuppressWarnings("unchecked")
                    @Override
                    public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<T> reader() {
                        return in -> (T) readerFor(action, in);
                    }
                });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private Object readerFor(String action, com.naqqa.elasticsearch.common.io.stream.StreamInput in) throws java.io.IOException {
        if (action.equals(ShardSearchService.ACTION_DFS)) {
            return new ShardSearchService.DfsResponse(in);
        }
        if (action.equals(ShardSearchService.ACTION_CAN_MATCH)) {
            return new ShardSearchService.CanMatchResponse(in);
        }
        if (action.equals(ShardSearchService.ACTION_QUERY)) {
            return new ShardSearchService.ShardQueryResponse(in);
        }
        if (action.equals(ShardSearchService.ACTION_FETCH)) {
            return new ShardSearchService.ShardFetchResponse(in);
        }
        throw new java.io.IOException("no reader for action [" + action + "]");
    }

    private Connection connectionFor(String nodeId) {
        return connections.computeIfAbsent(nodeId, id -> {
            DiscoveryNode node = nodes.get(id);
            if (node == null) {
                throw new IllegalStateException("unknown node [" + id + "]");
            }
            return transportService.connectToNode(node, ConnectionProfile.buildDefault());
        });
    }
}
