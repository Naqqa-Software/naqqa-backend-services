package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.common.UUIDs;
import com.naqqa.elasticsearch.http.RestStatusProvider;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.transport.Connection;
import com.naqqa.elasticsearch.transport.TransportException;
import com.naqqa.elasticsearch.transport.TransportRequestOptions;
import com.naqqa.elasticsearch.transport.TransportResponseHandler;
import com.naqqa.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class BulkCoordinator {

    public static final String BULK_SHARD_ACTION = "internal:write/bulk_shard";
    private static final long REMOTE_TIMEOUT_MILLIS = 30_000L;

    private final RelocationAwareRouter router;
    private final TransportIndexAction indexAction;
    private final TransportDeleteAction deleteAction;
    private final TransportUpdateAction updateAction;
    private final TransportService localTransportService;
    private final RemoteShardConnector remoteShardConnector;

    public BulkCoordinator(RelocationAwareRouter router, TransportIndexAction indexAction, TransportDeleteAction deleteAction,
                            TransportUpdateAction updateAction) {
        this(router, indexAction, deleteAction, updateAction, null, null);
    }

    public BulkCoordinator(RelocationAwareRouter router, TransportIndexAction indexAction, TransportDeleteAction deleteAction,
                            TransportUpdateAction updateAction, TransportService localTransportService,
                            RemoteShardConnector remoteShardConnector) {
        this.router = router;
        this.indexAction = indexAction;
        this.deleteAction = deleteAction;
        this.updateAction = updateAction;
        this.localTransportService = localTransportService;
        this.remoteShardConnector = remoteShardConnector;
    }

    public void registerShardHandler(TransportService transportService) {
        transportService.registerRequestHandler(BULK_SHARD_ACTION, BulkShardTransportRequest::new, (request, channel) -> {
            List<DocumentActionService.BulkItemResult> results = executeOnLocalShard(request.shardId(), request.items(),
                request.defaultIndex(), request.globalRefresh());
            channel.sendResponse(new BulkShardTransportResponse(results));
        });
    }

    public BulkResponse execute(BulkRequest request) {
        long start = System.nanoTime();
        List<DocumentActionService.BulkItem> items = request.items();
        int n = items.size();
        DocumentActionService.BulkItemResult[] resultsByPosition = new DocumentActionService.BulkItemResult[n];

        ClusterState state = router.clusterStateSupplier().getClusterState();
        Map<ShardId, List<Integer>> groupPositions = new LinkedHashMap<>();
        Map<ShardId, List<DocumentActionService.BulkItem>> groupItems = new LinkedHashMap<>();

        for (int i = 0; i < n; i++) {
            DocumentActionService.BulkItem item = items.get(i);
            String rawIndex = item.index() != null ? item.index() : request.defaultIndex();
            String effectiveIndex = rawIndex;
            String effectiveId = item.id();
            try {
                IndexNameResolver.Resolution resolution = IndexNameResolver.resolveForWrite(state, rawIndex);
                effectiveIndex = resolution.index();
                String routing = item.routing() != null ? item.routing() : resolution.routingOverride();
                if (effectiveId == null && !"delete".equals(item.action()) && !"update".equals(item.action())) {
                    effectiveId = UUIDs.base64TimeBasedUUID();
                }
                ShardId shardId = effectiveId != null
                    ? ShardRouter.resolveShardId(state, effectiveIndex, effectiveId, routing)
                    : null;
                if (shardId == null) {
                    resultsByPosition[i] = errorResult(item, effectiveIndex, new IllegalArgumentException(
                        "an id is required for bulk action [" + item.action() + "]"));
                    continue;
                }
                DocumentActionService.BulkItem resolved = withResolved(item, effectiveIndex, effectiveId, routing);
                groupPositions.computeIfAbsent(shardId, k -> new ArrayList<>()).add(i);
                groupItems.computeIfAbsent(shardId, k -> new ArrayList<>()).add(resolved);
            } catch (RuntimeException e) {
                resultsByPosition[i] = errorResult(item, effectiveIndex, e);
            }
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map.Entry<ShardId, List<DocumentActionService.BulkItem>> entry : groupItems.entrySet()) {
                ShardId shardId = entry.getKey();
                List<DocumentActionService.BulkItem> shardItems = entry.getValue();
                List<Integer> positions = groupPositions.get(shardId);
                futures.add(CompletableFuture.runAsync(() -> {
                    List<DocumentActionService.BulkItemResult> shardResults = dispatchShard(shardId, shardItems,
                        request.defaultIndex(), request.globalRefresh(), state);
                    for (int j = 0; j < shardResults.size(); j++) {
                        resultsByPosition[positions.get(j)] = shardResults.get(j);
                    }
                }, executor));
            }
            for (CompletableFuture<Void> future : futures) {
                future.join();
            }
        }

        List<DocumentActionService.BulkItemResult> ordered = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ordered.add(resultsByPosition[i]);
        }
        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        return new BulkResponse(ordered, tookMillis);
    }

    private List<DocumentActionService.BulkItemResult> dispatchShard(ShardId shardId, List<DocumentActionService.BulkItem> shardItems,
                                                                       String defaultIndex, String globalRefresh, ClusterState state) {
        if (router.replicationGroups().containsKey(shardId)) {
            return executeOnLocalShard(shardId, shardItems, defaultIndex, globalRefresh);
        }
        if (remoteShardConnector != null && localTransportService != null) {
            try {
                return executeOnRemoteShard(shardId, shardItems, defaultIndex, globalRefresh, state);
            } catch (Exception e) {
                return failAll(shardItems, defaultIndex, e);
            }
        }
        return failAll(shardItems, defaultIndex,
            new IllegalStateException("shard " + shardId + " is not locally available and no remote shard connector is configured"));
    }

    List<DocumentActionService.BulkItemResult> executeOnLocalShard(ShardId shardId, List<DocumentActionService.BulkItem> shardItems,
                                                                     String defaultIndex, String globalRefresh) {
        List<DocumentActionService.BulkItemResult> results = new ArrayList<>(shardItems.size());
        for (DocumentActionService.BulkItem item : shardItems) {
            results.add(executeItem(defaultIndex, item));
        }
        RefreshPolicy policy = RefreshPolicy.parse(globalRefresh);
        if (policy != RefreshPolicy.NONE) {
            var group = router.replicationGroups().get(shardId);
            if (group != null) {
                try {
                    RefreshCoordinator.apply(policy, group.primary().indexShard());
                } catch (IOException ignored) {
                }
            }
        }
        return results;
    }

    private List<DocumentActionService.BulkItemResult> executeOnRemoteShard(ShardId shardId, List<DocumentActionService.BulkItem> shardItems,
                                                                              String defaultIndex, String globalRefresh, ClusterState state)
        throws IOException, InterruptedException, ExecutionException, TimeoutException {
        Connection connection = remoteShardConnector.connect(shardId, state);
        BulkShardTransportRequest request = new BulkShardTransportRequest(shardId, defaultIndex, shardItems, globalRefresh);
        CompletableFuture<BulkShardTransportResponse> future = new CompletableFuture<>();
        localTransportService.sendRequest(connection, BULK_SHARD_ACTION, request,
            TransportRequestOptions.of().withTimeout(REMOTE_TIMEOUT_MILLIS),
            new TransportResponseHandler<BulkShardTransportResponse>() {
                @Override
                public void handleResponse(BulkShardTransportResponse response) {
                    future.complete(response);
                }

                @Override
                public void handleException(TransportException exp) {
                    future.completeExceptionally(exp);
                }

                @Override
                public com.naqqa.elasticsearch.common.io.stream.Writeable.Reader<BulkShardTransportResponse> reader() {
                    return BulkShardTransportResponse::new;
                }
            });
        BulkShardTransportResponse response = future.get(REMOTE_TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS);
        return response.results();
    }

    private DocumentActionService.BulkItemResult executeItem(String defaultIndex, DocumentActionService.BulkItem item) {
        String index = item.index() != null ? item.index() : defaultIndex;
        try {
            return switch (item.action()) {
                case "index", "create" -> {
                    String opType = "create".equals(item.action()) ? "create" : (item.opType() != null ? item.opType() : "index");
                    DocumentActionService.IndexRequest req = new DocumentActionService.IndexRequest(index, item.id(), item.source(),
                        item.routing(), item.version(), item.versionType(), item.ifSeqNo(), item.ifPrimaryTerm(), opType, "false", null);
                    DocumentActionService.IndexResult r = indexAction.execute(req);
                    yield new DocumentActionService.BulkItemResult(item.action(), r.index(), r.id(), r.created() ? 201 : 200,
                        r.version(), r.seqNo(), r.primaryTerm(), r.result(), true, null);
                }
                case "delete" -> {
                    DocumentActionService.DeleteRequest req = new DocumentActionService.DeleteRequest(index, item.id(), item.routing(),
                        item.version(), item.versionType(), item.ifSeqNo(), item.ifPrimaryTerm(), "false");
                    DocumentActionService.DeleteResult r = deleteAction.execute(req);
                    yield new DocumentActionService.BulkItemResult("delete", index, item.id(), r.found() ? 200 : 404,
                        r.version(), r.seqNo(), r.primaryTerm(), r.result(), r.found(), null);
                }
                case "update" -> {
                    DocumentActionService.UpdateRequest req = new DocumentActionService.UpdateRequest(index, item.id(), item.doc(),
                        item.upsert(), item.docAsUpsert(), true, 0, null, item.ifSeqNo(), item.ifPrimaryTerm(), "false", false);
                    DocumentActionService.UpdateResult r = updateAction.execute(req);
                    yield new DocumentActionService.BulkItemResult("update", index, item.id(),
                        "created".equals(r.result()) ? 201 : 200, r.version(), r.seqNo(), r.primaryTerm(), r.result(), true, null);
                }
                default -> throw new IllegalArgumentException("unsupported bulk action [" + item.action() + "]");
            };
        } catch (Exception e) {
            return errorResult(item, index, e);
        }
    }

    private static DocumentActionService.BulkItem withResolved(DocumentActionService.BulkItem item, String resolvedIndex,
                                                                 String resolvedId, String resolvedRouting) {
        return new DocumentActionService.BulkItem(item.action(), resolvedIndex, resolvedId, item.source(), item.doc(), item.upsert(),
            item.docAsUpsert(), item.version(), item.versionType(), item.ifSeqNo(), item.ifPrimaryTerm(), resolvedRouting,
            item.opType());
    }

    private static List<DocumentActionService.BulkItemResult> failAll(List<DocumentActionService.BulkItem> items, String defaultIndex,
                                                                        Exception cause) {
        List<DocumentActionService.BulkItemResult> results = new ArrayList<>(items.size());
        for (DocumentActionService.BulkItem item : items) {
            results.add(errorResult(item, item.index() != null ? item.index() : defaultIndex, cause));
        }
        return results;
    }

    private static DocumentActionService.BulkItemResult errorResult(DocumentActionService.BulkItem item, String index, Exception e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", typeNameOf(e));
        error.put("reason", e.getMessage());
        int status = e instanceof RestStatusProvider p ? p.restStatus() : 400;
        return new DocumentActionService.BulkItemResult(item.action(), index, item.id(), status, -1, -1, 0, null, false, error);
    }

    private static String typeNameOf(Throwable t) {
        String simple = t.getClass().getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        String snake = sb.toString();
        return snake.endsWith("_exception") ? snake : snake + "_exception";
    }
}
