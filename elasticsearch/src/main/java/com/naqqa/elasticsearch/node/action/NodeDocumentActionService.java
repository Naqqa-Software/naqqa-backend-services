package com.naqqa.elasticsearch.node.action;

import com.naqqa.elasticsearch.action.byquery.ByQueryActionService;
import com.naqqa.elasticsearch.action.write.DocumentActionServiceImpl;
import com.naqqa.elasticsearch.action.write.IndexNameResolver;
import com.naqqa.elasticsearch.cluster.state.ClusterBlockLevel;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.indices.IndexBlocks;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.IngestService;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.monitor.NodeCounters;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NodeDocumentActionService implements DocumentActionService {

    public interface AutoCreator {
        void ensureWriteTarget(String indexOrAlias);
    }

    private final DocumentActionServiceImpl writes;
    private final ByQueryActionService byQuery;
    private final ClusterStateManager clusterStateManager;
    private final IngestService ingestService;
    private final AutoCreator autoCreator;
    private final NodeCounters counters;
    private final ExecutorService writeExecutor;
    private final ExecutorService getExecutor;
    private final Function<String, List<String>> dataStreamBackingIndices;
    private volatile java.util.function.Consumer<String> refresher = index -> {
    };

    public interface DynamicMappingHook {
        void onDocument(String index, String id, String routing, Map<String, Object> source);
    }

    private volatile DynamicMappingHook dynamicMappings = (index, id, routing, source) -> {
    };

    public void setDynamicMappingHook(DynamicMappingHook hook) {
        this.dynamicMappings = hook;
    }

    private void afterWrite(String index, String id, String routing, Map<String, Object> source) {
        try {
            dynamicMappings.onDocument(index, id, routing, source);
        } catch (RuntimeException e) {
            System.err.println("[mapping] dynamic mapping hook failed: " + e.getMessage());
        }
    }

    public void setRefresher(java.util.function.Consumer<String> refresher) {
        this.refresher = refresher;
    }

    private CompletableFuture<Map<String, Object>> refreshAfter(CompletableFuture<Map<String, Object>> future, String index,
                                                                Map<String, String> params) {
        String refresh = params == null ? null : params.get("refresh");
        if (refresh == null || "false".equals(refresh)) {
            return future;
        }
        return future.thenApply(result -> {
            refresher.accept(index);
            return result;
        });
    }

    public NodeDocumentActionService(DocumentActionServiceImpl writes, ByQueryActionService byQuery,
                                     ClusterStateManager clusterStateManager, IngestService ingestService,
                                     AutoCreator autoCreator, NodeCounters counters, ExecutorService writeExecutor,
                                     ExecutorService getExecutor, Function<String, List<String>> dataStreamBackingIndices) {
        this.writes = writes;
        this.byQuery = byQuery;
        this.clusterStateManager = clusterStateManager;
        this.ingestService = ingestService;
        this.autoCreator = autoCreator;
        this.counters = counters;
        this.writeExecutor = writeExecutor;
        this.getExecutor = getExecutor;
        this.dataStreamBackingIndices = dataStreamBackingIndices;
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        }
    }

    private <T> CompletableFuture<T> onPool(ExecutorService pool, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, pool);
    }

    private String writeTarget(String indexOrAlias) {
        List<String> backing = dataStreamBackingIndices.apply(indexOrAlias);
        if (backing != null && !backing.isEmpty()) {
            return backing.get(backing.size() - 1);
        }
        return indexOrAlias;
    }

    private String prepareWrite(String indexOrAlias) {
        if (indexOrAlias == null || indexOrAlias.isEmpty()) {
            throw new RestApiException(400, "index is missing");
        }
        autoCreator.ensureWriteTarget(indexOrAlias);
        String target = writeTarget(indexOrAlias);
        ClusterState state = clusterStateManager.state();
        String concrete;
        try {
            concrete = IndexNameResolver.resolveForWrite(state, target).index();
        } catch (IndexNotFoundException e) {
            throw e;
        }
        IndexMetadata imd = state.getMetadata().index(concrete);
        if (imd != null && imd.getState() == IndexMetadata.State.CLOSE) {
            throw new RestApiException(400, "index [" + concrete + "] is closed");
        }
        if (state.getBlocks().hasIndexBlock(concrete, ClusterBlockLevel.WRITE)) {
            throw new RestApiException(403, "index [" + concrete + "] blocked by: [FORBIDDEN/8/index write (api)]");
        }
        IndexBlocks.checkBlockedBeforeWrite(state.getBlocks(), concrete);
        return target;
    }

    private List<String> pipelinesFor(String target, String requested) {
        List<String> out = new ArrayList<>();
        ClusterState state = clusterStateManager.state();
        String concrete;
        try {
            concrete = IndexNameResolver.resolveForWrite(state, target).index();
        } catch (RuntimeException e) {
            concrete = target;
        }
        IndexMetadata imd = state.getMetadata().index(concrete);
        if (requested != null && !requested.isEmpty() && !"_none".equals(requested)) {
            out.add(requested);
        } else if (requested == null && imd != null) {
            String defaultPipeline = imd.getSettings().get("index.default_pipeline");
            if (defaultPipeline != null && !"_none".equals(defaultPipeline)) {
                out.add(defaultPipeline);
            }
        }
        if (imd != null) {
            String finalPipeline = imd.getSettings().get("index.final_pipeline");
            if (finalPipeline != null && !"_none".equals(finalPipeline)) {
                out.add(finalPipeline);
            }
        }
        return out;
    }

    private static final Map<String, Object> DROPPED = Map.of("__dropped__", true);

    private IndexRequest applyIngest(IndexRequest request, String target) {
        List<String> pipelines = pipelinesFor(target, request.pipeline());
        if (pipelines.isEmpty()) {
            return request;
        }
        IngestDocument doc;
        try {
            IngestDocument input = new IngestDocument(target, request.id(), request.routing(), null, null,
                request.source() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.source()));
            doc = ingestService.executePipelines(pipelines, input);
        } catch (RestApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RestApiException(400, "pipeline execution failed: " + e.getMessage(), e);
        }
        if (doc == null) {
            return new IndexRequest(target, request.id(), DROPPED, null, null, null, null, null, null, null, null);
        }
        Map<String, Object> meta = doc.getSourceAndMetadata();
        String newIndex = meta.get("_index") != null ? String.valueOf(meta.get("_index")) : target;
        String newId = meta.get("_id") != null ? String.valueOf(meta.get("_id")) : request.id();
        String newRouting = meta.get("_routing") != null ? String.valueOf(meta.get("_routing")) : request.routing();
        if (!newIndex.equals(target)) {
            prepareWrite(newIndex);
        }
        return new IndexRequest(newIndex, newId, doc.getSource(), newRouting, request.version(), request.versionType(),
            request.ifSeqNo(), request.ifPrimaryTerm(), request.opType(), request.refresh(), "_none");
    }

    @Override
    public CompletableFuture<IndexResult> index(IndexRequest request) {
        return onPool(writeExecutor, () -> {
            long start = counters.indexing.start();
            boolean ok = false;
            try {
                String target = prepareWrite(request.index());
                IndexRequest effective = applyIngest(new IndexRequest(target, request.id(), request.source(), request.routing(),
                    request.version(), request.versionType(), request.ifSeqNo(), request.ifPrimaryTerm(), request.opType(),
                    request.refresh(), request.pipeline()), target);
                if (effective.source() == DROPPED) {
                    ok = true;
                    return new IndexResult(target, request.id(), -1, -3, 0, "noop", false, 0, 0);
                }
                IndexResult result = join(writes.index(effective));
                afterWrite(result.index(), result.id(), effective.routing(), effective.source());
                ok = true;
                return result;
            } finally {
                counters.indexing.end(start, ok);
            }
        });
    }

    @Override
    public CompletableFuture<GetResult> get(GetRequest request) {
        return onPool(getExecutor, () -> {
            long start = counters.getExists.start();
            GetResult result = null;
            try {
                GetRequest effective = new GetRequest(writeTarget(request.index()), request.id(), request.routing(),
                    request.sourceEnabled(), request.sourceIncludes(), request.sourceExcludes(), request.storedFields(),
                    request.version(), request.realtime(), request.refresh());
                result = join(writes.get(effective));
                return result;
            } finally {
                if (result != null && !result.found()) {
                    counters.getExists.end(start, true);
                    long missStart = counters.getMissing.start();
                    counters.getMissing.end(missStart, true);
                } else {
                    counters.getExists.end(start, result != null);
                }
            }
        });
    }

    @Override
    public CompletableFuture<DeleteResult> delete(DeleteRequest request) {
        return onPool(writeExecutor, () -> {
            long start = counters.deletes.start();
            boolean ok = false;
            try {
                String target = writeTarget(request.index());
                ClusterState state = clusterStateManager.state();
                String concrete = IndexNameResolver.resolveForWrite(state, target).index();
                IndexBlocks.checkBlockedBeforeWrite(state.getBlocks(), concrete);
                DeleteResult result = join(writes.delete(new DeleteRequest(target, request.id(), request.routing(),
                    request.version(), request.versionType(), request.ifSeqNo(), request.ifPrimaryTerm(), request.refresh())));
                ok = true;
                return result;
            } finally {
                counters.deletes.end(start, ok);
            }
        });
    }

    @Override
    public CompletableFuture<UpdateResult> update(UpdateRequest request) {
        return onPool(writeExecutor, () -> {
            long start = counters.indexing.start();
            boolean ok = false;
            try {
                String target = prepareWrite(request.index());
                UpdateResult result = join(writes.update(new UpdateRequest(target, request.id(), request.doc(), request.upsert(),
                    request.docAsUpsert(), request.detectNoop(), request.retryOnConflict(), request.script(),
                    request.ifSeqNo(), request.ifPrimaryTerm(), request.refresh(), request.sourceEnabled())));
                if (request.doc() != null) {
                    afterWrite(result.index(), result.id(), null, request.doc());
                } else if (request.upsert() != null) {
                    afterWrite(result.index(), result.id(), null, request.upsert());
                }
                ok = true;
                return result;
            } finally {
                counters.indexing.end(start, ok);
            }
        });
    }

    @Override
    public CompletableFuture<BulkResult> bulk(List<BulkItem> items, String defaultIndex, String globalRefresh) {
        return onPool(writeExecutor, () -> {
            long startNanos = System.nanoTime();
            Set<String> targets = new LinkedHashSet<>();
            List<BulkItem> effective = new ArrayList<>(items.size());
            Map<Integer, BulkItemResult> preFailed = new LinkedHashMap<>();
            for (int i = 0; i < items.size(); i++) {
                BulkItem item = items.get(i);
                String index = item.index() != null ? item.index() : defaultIndex;
                try {
                    String target = "delete".equals(item.action()) ? writeTarget(index) : prepareWrite(index);
                    targets.add(target);
                    Map<String, Object> source = item.source();
                    String id = item.id();
                    String routing = item.routing();
                    if (("index".equals(item.action()) || "create".equals(item.action())) && source != null) {
                        List<String> pipelines = pipelinesFor(target, null);
                        if (!pipelines.isEmpty()) {
                            IngestDocument doc = ingestService.executePipelines(pipelines,
                                new IngestDocument(target, id, routing, null, null, new LinkedHashMap<>(source)));
                            if (doc == null) {
                                preFailed.put(i, new BulkItemResult(item.action(), target, id, 200, -1, -3, 0, "noop", false, null));
                                continue;
                            }
                            source = doc.getSource();
                        }
                    }
                    effective.add(new BulkItem(item.action(), target, id, source, item.doc(), item.upsert(), item.docAsUpsert(),
                        item.version(), item.versionType(), item.ifSeqNo(), item.ifPrimaryTerm(), routing, item.opType()));
                } catch (Exception e) {
                    int status = e instanceof RestApiException rae ? rae.restStatus() : 400;
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("type", e.getClass().getSimpleName());
                    error.put("reason", String.valueOf(e.getMessage()));
                    preFailed.put(i, new BulkItemResult(item.action(), index, item.id(), status, -1, -2, 0, null, false, error));
                }
            }
            long start = counters.indexing.start();
            boolean ok = false;
            try {
                BulkResult result = effective.isEmpty() ? new BulkResult(List.of(), 0L)
                    : join(writes.bulk(effective, defaultIndex, globalRefresh));
                List<BulkItemResult> merged = new ArrayList<>(items.size());
                int cursor = 0;
                for (int i = 0; i < items.size(); i++) {
                    BulkItemResult pre = preFailed.get(i);
                    if (pre != null) {
                        merged.add(pre);
                    } else {
                        BulkItem sent = effective.get(cursor);
                        BulkItemResult itemResult = result.items().get(cursor++);
                        merged.add(itemResult);
                        if (itemResult.status() < 300 && itemResult.error() == null) {
                            Map<String, Object> written = sent.source() != null ? sent.source() : sent.doc();
                            if (written != null) {
                                afterWrite(itemResult.index(), itemResult.id(), sent.routing(), written);
                            }
                        }
                    }
                }
                ok = true;
                return new BulkResult(merged, (System.nanoTime() - startNanos) / 1_000_000L);
            } finally {
                counters.indexing.end(start, ok);
            }
        });
    }

    @Override
    public CompletableFuture<MultiGetResult> multiGet(List<GetRequest> requests) {
        return onPool(getExecutor, () -> {
            List<GetResult> docs = new ArrayList<>();
            for (GetRequest request : requests) {
                try {
                    docs.add(join(get(request)));
                } catch (IndexNotFoundException e) {
                    docs.add(new GetResult(request.index(), request.id(), false, -1, -1, 0, null));
                }
            }
            return new MultiGetResult(docs);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> deleteByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        return refreshAfter(byQuery.deleteByQuery(index, requestBody, params), index, params);
    }

    @Override
    public CompletableFuture<Map<String, Object>> updateByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        return refreshAfter(byQuery.updateByQuery(index, requestBody, params), index, params);
    }

    @Override
    public CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody) {
        Map<String, Object> dest = requestBody == null ? null : com.naqqa.elasticsearch.node.support.SettingsMaps.asMap(requestBody.get("dest"));
        if (dest != null && dest.get("index") != null) {
            try {
                autoCreator.ensureWriteTarget(String.valueOf(dest.get("index")));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        return byQuery.reindex(requestBody);
    }

    @Override
    public CompletableFuture<Map<String, Object>> termVectors(String index, String id, Map<String, Object> requestBody) {
        return byQuery.termVectors(index, id, requestBody);
    }

    @Override
    public CompletableFuture<Map<String, Object>> multiTermVectors(Map<String, Object> requestBody) {
        return byQuery.multiTermVectors(requestBody);
    }

    @Override
    public CompletableFuture<Map<String, Object>> rethrottle(String taskId, Double requestsPerSecond) {
        return byQuery.rethrottle(taskId, requestsPerSecond);
    }
}
