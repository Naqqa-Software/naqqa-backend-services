package com.naqqa.elasticsearch.node.support;

import com.naqqa.elasticsearch.action.write.IndexNameResolver;
import com.naqqa.elasticsearch.action.write.ShardRouter;
import com.naqqa.elasticsearch.action.write.SourceUtils;
import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.common.UUIDs;
import com.naqqa.elasticsearch.common.logging.ESLogger;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.http.DefaultRestErrorRenderer;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.cluster.NodeConnections;
import com.naqqa.elasticsearch.node.cluster.Wire;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.rest.support.VersionConflictException;
import com.naqqa.elasticsearch.transport.TransportService;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class ClusterDocumentActionService implements DocumentActionService, AutoCloseable {

    private static final ESLogger LOG = ESLogger.getLogger(ClusterDocumentActionService.class);

    public static final String INDEX_ACTION = "internal:node/document/index";
    public static final String DELETE_ACTION = "internal:node/document/delete";
    public static final String UPDATE_ACTION = "internal:node/document/update";
    public static final String GET_ACTION = "internal:node/document/get";
    public static final String DELETE_BY_QUERY_ACTION = "internal:node/document/delete_by_query";
    public static final String UPDATE_BY_QUERY_ACTION = "internal:node/document/update_by_query";
    public static final String REINDEX_ACTION = "internal:node/document/reindex";
    private static final long FORWARD_TIMEOUT_MILLIS = 60_000L;
    private static final int MAX_ATTEMPTS = 40;

    private final DocumentActionService local;
    private final ClusterStateManager clusterStateManager;
    private final IndicesService indicesService;
    private final TransportService transportService;
    private final NodeConnections connections;
    private final Function<String, List<String>> dataStreamBackingIndices;
    private final Consumer<String> autoCreator;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final DefaultRestErrorRenderer statusResolver = new DefaultRestErrorRenderer();

    public ClusterDocumentActionService(DocumentActionService local, ClusterStateManager clusterStateManager,
                                        IndicesService indicesService, TransportService transportService, NodeConnections connections,
                                        Function<String, List<String>> dataStreamBackingIndices, Consumer<String> autoCreator) {
        this.local = local;
        this.clusterStateManager = clusterStateManager;
        this.indicesService = indicesService;
        this.transportService = transportService;
        this.connections = connections;
        this.dataStreamBackingIndices = dataStreamBackingIndices;
        this.autoCreator = autoCreator;
        Wire.register(transportService, INDEX_ACTION, payload -> handle(local.index(fromMap(IndexRequest.class, Wire.decode(payload)))));
        Wire.register(transportService, DELETE_ACTION, payload -> handle(local.delete(fromMap(DeleteRequest.class, Wire.decode(payload)))));
        Wire.register(transportService, UPDATE_ACTION, payload -> handle(local.update(fromMap(UpdateRequest.class, Wire.decode(payload)))));
        Wire.register(transportService, GET_ACTION, payload -> CompletableFuture.supplyAsync(() -> Wire.encode(readLocal(Wire.decode(payload))),
            executor));
        Wire.register(transportService, DELETE_BY_QUERY_ACTION,
            payload -> byQueryHandler(payload, local::deleteByQuery));
        Wire.register(transportService, UPDATE_BY_QUERY_ACTION,
            payload -> byQueryHandler(payload, local::updateByQuery));
        Wire.register(transportService, REINDEX_ACTION,
            payload -> byQueryHandler(payload, (idx, body, params) -> local.reindex(body)));
    }

    private interface ByQueryCall {
        CompletableFuture<Map<String, Object>> call(String index, Map<String, Object> body, Map<String, String> params);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<byte[]> byQueryHandler(byte[] payload, ByQueryCall call) {
        Map<String, Object> req = Wire.decode(payload);
        String index = req.get("index") == null ? null : String.valueOf(req.get("index"));
        Map<String, Object> body = SettingsMaps.asMap(req.get("body"));
        Map<String, String> params = new LinkedHashMap<>();
        if (req.get("params") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                params.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return call.call(index, body, params).handle((result, error) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            if (error != null) {
                Throwable cause = Wire.unwrap(error);
                out.put("error", Map.of("reason", String.valueOf(cause.getMessage())));
            } else {
                out.put("result", result);
            }
            return Wire.encode(out);
        });
    }

    private Set<String> primaryOwningNodeIds(ClusterState state, String indexExpr) {
        Set<String> indices = new LinkedHashSet<>();
        if (indexExpr != null) {
            for (String part : indexExpr.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (state.getMetadata().index(p) != null) {
                    indices.add(p);
                } else {
                    indices.addAll(state.getMetadata().resolveIndicesForAlias(p));
                }
            }
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        for (String idx : indices) {
            IndexRoutingTable irt = state.getRoutingTable().index(idx);
            if (irt == null) {
                continue;
            }
            for (IndexShardRoutingTable table : irt.getShards().values()) {
                ShardRouting primary = table.primaryShard();
                if (primary != null && primary.currentNodeId() != null && state.getNodes().nodeExists(primary.currentNodeId())) {
                    nodeIds.add(primary.currentNodeId());
                }
            }
        }
        return nodeIds;
    }

    private Map<String, Object> broadcastByQuery(String action, String index, Map<String, Object> body, Map<String, String> params,
                                                  Supplier<CompletableFuture<Map<String, Object>>> localCall) {
        ClusterState state = clusterStateManager.state();
        Set<String> nodeIds = primaryOwningNodeIds(state, index);
        String localId = clusterStateManager.localNode().getId();
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(join(localCall.get()));
        Map<String, Object> requestPayload = new LinkedHashMap<>();
        requestPayload.put("index", index);
        requestPayload.put("body", body);
        requestPayload.put("params", params);
        byte[] payload = Wire.encode(requestPayload);
        for (String nodeId : nodeIds) {
            if (nodeId.equals(localId)) {
                continue;
            }
            DiscoveryNode node = state.getNodes().get(nodeId);
            if (node == null) {
                continue;
            }
            try {
                Map<String, Object> resp = Wire.decode(Wire.sendSync(transportService, connections.get(node), action, payload,
                    FORWARD_TIMEOUT_MILLIS));
                Object error = resp.get("error");
                if (error instanceof Map<?, ?> err) {
                    throw new RestApiException(500, String.valueOf(err.get("reason")));
                }
                results.add(SettingsMaps.asMap(resp.get("result")));
            } catch (Exception e) {
                LOG.warn("[document] by-query broadcast to [" + node.getName() + "] failed: " + e);
            }
        }
        return mergeByQueryResults(results);
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Map<String, Object> mergeByQueryResults(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return Map.of();
        }
        long updated = 0;
        long created = 0;
        long deleted = 0;
        long noops = 0;
        long versionConflicts = 0;
        long bulkRetries = 0;
        int batches = 0;
        long took = 0;
        long total = 0;
        boolean timedOut = false;
        List<Object> failures = new ArrayList<>();
        for (Map<String, Object> r : results) {
            if (r == null) {
                continue;
            }
            updated += asLong(r.get("updated"));
            created += asLong(r.get("created"));
            deleted += asLong(r.get("deleted"));
            noops += asLong(r.get("noops"));
            versionConflicts += asLong(r.get("version_conflicts"));
            batches += (int) asLong(r.get("batches"));
            took = Math.max(took, asLong(r.get("took")));
            total = Math.max(total, asLong(r.get("total")));
            timedOut |= Boolean.TRUE.equals(r.get("timed_out"));
            if (r.get("failures") instanceof List<?> list) {
                failures.addAll(list);
            }
            if (r.get("retries") instanceof Map<?, ?> rm) {
                bulkRetries += asLong(rm.get("bulk"));
            }
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("took", took);
        merged.put("timed_out", timedOut);
        merged.put("total", total);
        merged.put("updated", updated);
        merged.put("created", created);
        merged.put("deleted", deleted);
        merged.put("batches", batches);
        merged.put("version_conflicts", versionConflicts);
        merged.put("noops", noops);
        Map<String, Object> retriesMap = new LinkedHashMap<>();
        retriesMap.put("bulk", bulkRetries);
        retriesMap.put("search", 0);
        merged.put("retries", retriesMap);
        merged.put("throttled_millis", 0L);
        merged.put("requests_per_second", -1.0);
        merged.put("throttled_until_millis", 0L);
        merged.put("failures", failures);
        return merged;
    }

    private boolean distributed() {
        return clusterStateManager.isMultiNode();
    }

    private CompletableFuture<byte[]> handle(CompletableFuture<?> future) {
        return future.handle((result, error) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            if (error != null) {
                Throwable cause = Wire.unwrap(error);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("class", cause.getClass().getName());
                err.put("status", statusResolver.statusFor(cause));
                err.put("reason", String.valueOf(cause.getMessage()));
                if (cause instanceof IndexNotFoundException && clusterStateManager.state().getMetadata().index(indexFromMessage(cause)) != null) {
                    err.put("retry", true);
                }
                out.put("error", err);
            } else {
                out.put("result", toMap((Record) result));
            }
            return Wire.encode(out);
        });
    }

    private static String indexFromMessage(Throwable cause) {
        String message = String.valueOf(cause.getMessage());
        int open = message.indexOf('[');
        int close = message.lastIndexOf(']');
        return open >= 0 && close > open ? message.substring(open + 1, close) : message;
    }

    private record Target(String index, ShardId shardId, IndexShardRoutingTable table) {
    }

    private String writeTarget(String indexOrAlias) {
        List<String> backing = dataStreamBackingIndices == null ? null : dataStreamBackingIndices.apply(indexOrAlias);
        if (backing != null && !backing.isEmpty()) {
            return backing.get(backing.size() - 1);
        }
        return indexOrAlias;
    }

    private Target resolve(ClusterState state, String indexOrAlias, String id, String routing) {
        IndexNameResolver.Resolution resolution = IndexNameResolver.resolveForWrite(state, writeTarget(indexOrAlias));
        String effectiveRouting = routing != null ? routing : resolution.routingOverride();
        ShardId shardId = ShardRouter.resolveShardId(state, resolution.index(), id, effectiveRouting);
        IndexRoutingTable irt = state.getRoutingTable().index(shardId.index());
        IndexShardRoutingTable table = irt == null ? null : irt.shard(shardId.id());
        return new Target(resolution.index(), shardId, table);
    }

    private interface Forwardable<T> {
        CompletableFuture<T> runLocally();
    }

    private <T> T route(String indexOrAlias, String id, String routing, boolean autoCreate, String action, Record request,
                        Class<T> resultType, Forwardable<T> localCall) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ClusterState state = clusterStateManager.state();
            Target target;
            try {
                target = resolve(state, indexOrAlias, id, routing);
            } catch (IndexNotFoundException e) {
                if (autoCreate && attempt == 0 && autoCreator != null) {
                    autoCreator.accept(indexOrAlias);
                    continue;
                }
                return join(localCall.runLocally());
            }
            ShardRouting primary = target.table() == null ? null : target.table().primaryShard();
            if (primary == null || !primary.active() || primary.currentNodeId() == null
                || !state.getNodes().nodeExists(primary.currentNodeId())) {
                last = new RestApiException(503, "primary shard " + target.shardId() + " is not active");
                pause();
                continue;
            }
            if (primary.currentNodeId().equals(clusterStateManager.localNode().getId())) {
                if (!indicesService.isLocalPrimary(target.shardId())) {
                    last = new RestApiException(503, "primary shard " + target.shardId() + " is not yet started on this node");
                    pause();
                    continue;
                }
                try {
                    return join(localCall.runLocally());
                } catch (IndexNotFoundException e) {
                    if (state.getMetadata().index(target.index()) == null) {
                        throw e;
                    }
                    last = e;
                    pause();
                    continue;
                }
            }
            DiscoveryNode node = state.getNodes().get(primary.currentNodeId());
            Map<String, Object> response;
            try {
                response = Wire.decode(Wire.sendSync(transportService, connections.get(node), action, Wire.encode(toMap(request)),
                    FORWARD_TIMEOUT_MILLIS));
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new RestApiException(503, e.getMessage(), e);
                pause();
                continue;
            }
            Object error = response.get("error");
            if (error instanceof Map<?, ?> err) {
                if (Boolean.TRUE.equals(err.get("retry"))) {
                    last = new RestApiException(503, String.valueOf(err.get("reason")));
                    pause();
                    continue;
                }
                throw rebuild(err);
            }
            return fromMap(resultType, SettingsMaps.asMap(response.get("result")));
        }
        throw last != null ? last : new RestApiException(503, "unable to route request for [" + indexOrAlias + "]");
    }

    private static RuntimeException rebuild(Map<?, ?> err) {
        String cls = String.valueOf(err.get("class"));
        String reason = String.valueOf(err.get("reason"));
        int status = err.get("status") instanceof Number n ? n.intValue() : 500;
        if (cls.endsWith(".VersionConflictException")) {
            return new VersionConflictException(reason);
        }
        if (cls.endsWith(".IndexNotFoundException")) {
            int open = reason.indexOf('[');
            int close = reason.lastIndexOf(']');
            return new IndexNotFoundException(open >= 0 && close > open ? reason.substring(open + 1, close) : reason);
        }
        return new RestApiException(status, reason);
    }

    private static void pause() {
        try {
            Thread.sleep(150L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = Wire.unwrap(e);
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        }
    }

    @Override
    public CompletableFuture<IndexResult> index(IndexRequest request) {
        if (!distributed()) {
            return local.index(request);
        }
        IndexRequest effective = request.id() != null ? request : new IndexRequest(request.index(), UUIDs.base64TimeBasedUUID(),
            request.source(), request.routing(), request.version(), request.versionType(), request.ifSeqNo(), request.ifPrimaryTerm(),
            request.opType() == null ? "create" : request.opType(), request.refresh(), request.pipeline());
        return CompletableFuture.supplyAsync(() -> route(effective.index(), effective.id(), effective.routing(), true, INDEX_ACTION,
            effective, IndexResult.class, () -> local.index(effective)), executor);
    }

    @Override
    public CompletableFuture<DeleteResult> delete(DeleteRequest request) {
        if (!distributed()) {
            return local.delete(request);
        }
        return CompletableFuture.supplyAsync(() -> route(request.index(), request.id(), request.routing(), false, DELETE_ACTION,
            request, DeleteResult.class, () -> local.delete(request)), executor);
    }

    @Override
    public CompletableFuture<UpdateResult> update(UpdateRequest request) {
        if (!distributed()) {
            return local.update(request);
        }
        return CompletableFuture.supplyAsync(() -> route(request.index(), request.id(), null, true, UPDATE_ACTION,
            request, UpdateResult.class, () -> local.update(request)), executor);
    }

    @Override
    public CompletableFuture<GetResult> get(GetRequest request) {
        if (!distributed()) {
            return local.get(request);
        }
        return CompletableFuture.supplyAsync(() -> distributedGet(request), executor);
    }

    private GetResult distributedGet(GetRequest request) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            ClusterState state = clusterStateManager.state();
            Target target = resolve(state, request.index(), request.id(), request.routing());
            List<ShardRouting> started = new ArrayList<>();
            ShardRouting localCopy = null;
            if (target.table() != null) {
                for (ShardRouting sr : target.table().getShards()) {
                    if (sr.active() && sr.currentNodeId() != null && state.getNodes().nodeExists(sr.currentNodeId())) {
                        started.add(sr);
                        if (sr.currentNodeId().equals(clusterStateManager.localNode().getId())) {
                            localCopy = sr;
                        }
                    }
                }
            }
            if (started.isEmpty()) {
                last = new RestApiException(503, "no active copy of shard " + target.shardId() + " is available");
                pause();
                continue;
            }
            Map<String, Object> read = new LinkedHashMap<>();
            read.put("index", target.shardId().index());
            read.put("shard", target.shardId().id());
            read.put("id", request.id());
            read.put("refresh", request.refresh());
            Map<String, Object> response;
            try {
                if (localCopy != null && indicesService.shard(target.shardId()) != null) {
                    response = readLocal(read);
                } else {
                    ShardRouting chosen = started.get(0);
                    for (ShardRouting sr : started) {
                        if (sr.primary()) {
                            chosen = sr;
                        }
                    }
                    DiscoveryNode node = state.getNodes().get(chosen.currentNodeId());
                    response = Wire.decode(Wire.sendSync(transportService, connections.get(node), GET_ACTION, Wire.encode(read),
                        FORWARD_TIMEOUT_MILLIS));
                }
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new RestApiException(503, e.getMessage(), e);
                pause();
                continue;
            }
            if (Boolean.TRUE.equals(response.get("missing_shard"))) {
                last = new RestApiException(503, "shard " + target.shardId() + " is not available on the selected node");
                pause();
                continue;
            }
            if (!Boolean.TRUE.equals(response.get("found"))) {
                return new GetResult(target.index(), request.id(), false, -1, -1, 0, null);
            }
            Map<String, Object> source = null;
            if (request.sourceEnabled() && response.get("source") instanceof byte[] bytes && bytes.length > 0) {
                source = SettingsMaps.asMap(JsonValue.parse(bytes).toJava());
            }
            source = SourceUtils.filterSource(source, request.sourceIncludes(), request.sourceExcludes());
            return new GetResult(target.index(), request.id(), true, ((Number) response.get("version")).longValue(),
                ((Number) response.get("seq_no")).longValue(), ((Number) response.get("primary_term")).longValue(), source);
        }
        throw last != null ? last : new RestApiException(503, "unable to route get for [" + request.index() + "]");
    }

    private Map<String, Object> readLocal(Map<String, Object> request) {
        ShardId shardId = new ShardId(String.valueOf(request.get("index")), ((Number) request.get("shard")).intValue());
        IndexShard shard = indicesService.shard(shardId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (shard == null) {
            out.put("missing_shard", true);
            return out;
        }
        try {
            String id = String.valueOf(request.get("id"));
            if (Boolean.TRUE.equals(request.get("refresh"))) {
                shard.refresh();
            }
            com.naqqa.elasticsearch.index.engine.GetResult result = shard.get(id);
            out.put("found", result.exists());
            if (result.exists()) {
                out.put("version", result.version());
                out.put("seq_no", result.seqNo());
                out.put("primary_term", result.primaryTerm());
                out.put("source", result.source() == null ? new byte[0] : result.source().toBytesArray());
            }
            return out;
        } catch (java.io.IOException e) {
            throw new RestApiException(500, e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<BulkResult> bulk(List<BulkItem> items, String defaultIndex, String globalRefresh) {
        return local.bulk(items, defaultIndex, globalRefresh);
    }

    @Override
    public CompletableFuture<MultiGetResult> multiGet(List<GetRequest> requests) {
        if (!distributed()) {
            return local.multiGet(requests);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<GetResult> docs = new ArrayList<>();
            for (GetRequest request : requests) {
                try {
                    docs.add(distributedGet(request));
                } catch (IndexNotFoundException e) {
                    docs.add(new GetResult(request.index(), request.id(), false, -1, -1, 0, null));
                }
            }
            return new MultiGetResult(docs);
        }, executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> deleteByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        if (!distributed()) {
            return local.deleteByQuery(index, requestBody, params);
        }
        return CompletableFuture.supplyAsync(() -> broadcastByQuery(DELETE_BY_QUERY_ACTION, index, requestBody, params,
            () -> local.deleteByQuery(index, requestBody, params)), executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> updateByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        if (!distributed()) {
            return local.updateByQuery(index, requestBody, params);
        }
        return CompletableFuture.supplyAsync(() -> broadcastByQuery(UPDATE_BY_QUERY_ACTION, index, requestBody, params,
            () -> local.updateByQuery(index, requestBody, params)), executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody) {
        if (!distributed()) {
            return local.reindex(requestBody);
        }
        String resolvedSourceIndex = null;
        if (requestBody != null && requestBody.get("source") instanceof Map<?, ?> source && source.get("index") != null) {
            resolvedSourceIndex = String.valueOf(source.get("index"));
        }
        String sourceIndex = resolvedSourceIndex;
        return CompletableFuture.supplyAsync(() -> broadcastByQuery(REINDEX_ACTION, sourceIndex, requestBody, Map.of(),
            () -> local.reindex(requestBody)), executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> termVectors(String index, String id, Map<String, Object> requestBody) {
        return local.termVectors(index, id, requestBody);
    }

    @Override
    public CompletableFuture<Map<String, Object>> multiTermVectors(Map<String, Object> requestBody) {
        return local.multiTermVectors(requestBody);
    }

    @Override
    public CompletableFuture<Map<String, Object>> rethrottle(String taskId, Double requestsPerSecond) {
        return local.rethrottle(taskId, requestsPerSecond);
    }

    static Map<String, Object> toMap(Record record) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                out.put(component.getName(), component.getAccessor().invoke(record));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot serialize " + record.getClass().getSimpleName(), e);
        }
        return out;
    }

    static <T> T fromMap(Class<T> type, Map<String, Object> values) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = coerce(types[i], values == null ? null : values.get(components[i].getName()));
        }
        try {
            Constructor<T> constructor = type.getDeclaredConstructor(types);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot deserialize " + type.getSimpleName(), e);
        }
    }

    private static Object coerce(Class<?> type, Object value) {
        if (type == long.class) {
            return value instanceof Number n ? n.longValue() : 0L;
        }
        if (type == int.class) {
            return value instanceof Number n ? n.intValue() : 0;
        }
        if (type == boolean.class) {
            return Boolean.TRUE.equals(value);
        }
        if (value == null) {
            return null;
        }
        if (type == Long.class) {
            return ((Number) value).longValue();
        }
        if (type == Integer.class) {
            return ((Number) value).intValue();
        }
        if (type == Double.class) {
            return ((Number) value).doubleValue();
        }
        if (type == Map.class) {
            return SettingsMaps.asMap(value);
        }
        return value;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
