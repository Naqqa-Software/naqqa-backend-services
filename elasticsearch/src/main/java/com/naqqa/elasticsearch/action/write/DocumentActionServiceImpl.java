package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.replication.ShardCopy;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class DocumentActionServiceImpl implements DocumentActionService {

    private final RelocationAwareRouter router;
    private final TransportIndexAction indexAction;
    private final TransportDeleteAction deleteAction;
    private final TransportUpdateAction updateAction;
    private final BulkCoordinator bulkCoordinator;
    private final AtomicInteger readCopyCounter = new AtomicInteger();

    public DocumentActionServiceImpl(RelocationAwareRouter router) {
        this(router, null, null, null);
    }

    public DocumentActionServiceImpl(RelocationAwareRouter router, UpdateScriptExecutor scriptExecutor,
                                      TransportService localTransportService, RemoteShardConnector remoteShardConnector) {
        this.router = router;
        this.indexAction = new TransportIndexAction(router);
        this.deleteAction = new TransportDeleteAction(router);
        this.updateAction = new TransportUpdateAction(router, scriptExecutor);
        this.bulkCoordinator = new BulkCoordinator(router, indexAction, deleteAction, updateAction, localTransportService,
            remoteShardConnector);
    }

    public BulkCoordinator bulkCoordinator() {
        return bulkCoordinator;
    }

    @Override
    public CompletableFuture<IndexResult> index(IndexRequest request) {
        try {
            return CompletableFuture.completedFuture(indexAction.execute(request));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<GetResult> get(GetRequest request) {
        try {
            ClusterState state = router.clusterStateSupplier().getClusterState();
            IndexNameResolver.Resolution resolution = IndexNameResolver.resolveForWrite(state, request.index());
            String index = resolution.index();
            String routing = request.routing() != null ? request.routing() : resolution.routingOverride();
            ShardId shardId = ShardRouter.resolveShardId(state, index, request.id(), routing);
            ReplicationGroup group = router.groupFor(shardId);
            ShardCopy copy = selectReadCopy(group);
            com.naqqa.elasticsearch.index.engine.GetResult result = copy.indexShard().get(request.id());
            if (request.refresh()) {
                copy.indexShard().refresh();
                result = copy.indexShard().get(request.id());
            }
            if (!result.exists()) {
                return CompletableFuture.completedFuture(new GetResult(index, request.id(), false, -1, -1, 0, null));
            }
            Map<String, Object> source = request.sourceEnabled() ? SourceUtils.decode(result.source()) : null;
            source = SourceUtils.filterSource(source, request.sourceIncludes(), request.sourceExcludes());
            return CompletableFuture.completedFuture(new GetResult(index, request.id(), true, result.version(),
                result.seqNo(), result.primaryTerm(), source));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ShardCopy selectReadCopy(ReplicationGroup group) {
        List<ShardCopy> candidates = new ArrayList<>();
        candidates.add(group.primary());
        candidates.addAll(group.inSyncReplicasSnapshot());
        int index = Math.floorMod(readCopyCounter.getAndIncrement(), candidates.size());
        return candidates.get(index);
    }

    @Override
    public CompletableFuture<DeleteResult> delete(DeleteRequest request) {
        try {
            return CompletableFuture.completedFuture(deleteAction.execute(request));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<UpdateResult> update(UpdateRequest request) {
        try {
            return CompletableFuture.completedFuture(updateAction.execute(request));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<BulkResult> bulk(List<BulkItem> items, String defaultIndex, String globalRefresh) {
        try {
            BulkResponse response = bulkCoordinator.execute(new BulkRequest(items, defaultIndex, globalRefresh));
            return CompletableFuture.completedFuture(new BulkResult(response.items(), response.tookMillis()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<MultiGetResult> multiGet(List<GetRequest> requests) {
        List<GetResult> results = new ArrayList<>(requests.size());
        for (GetRequest request : requests) {
            try {
                results.add(get(request).join());
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return CompletableFuture.failedFuture(cause);
            }
        }
        return CompletableFuture.completedFuture(new MultiGetResult(results));
    }

    @Override
    public CompletableFuture<Map<String, Object>> deleteByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        return outOfScope("_delete_by_query");
    }

    @Override
    public CompletableFuture<Map<String, Object>> updateByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        return outOfScope("_update_by_query");
    }

    @Override
    public CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody) {
        return outOfScope("_reindex");
    }

    @Override
    public CompletableFuture<Map<String, Object>> termVectors(String index, String id, Map<String, Object> requestBody) {
        return outOfScope("_termvectors");
    }

    @Override
    public CompletableFuture<Map<String, Object>> multiTermVectors(Map<String, Object> requestBody) {
        return outOfScope("_mtermvectors");
    }

    @Override
    public CompletableFuture<Map<String, Object>> rethrottle(String taskId, Double requestsPerSecond) {
        return outOfScope("_rethrottle");
    }

    private static <T> CompletableFuture<T> outOfScope(String api) {
        return CompletableFuture.failedFuture(new RestApiException(501, api
            + " requires the query-execution and/or scripting subsystems, which are outside the distributed-write component"));
    }
}
