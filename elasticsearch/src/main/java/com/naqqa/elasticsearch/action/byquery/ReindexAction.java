package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.write.IndexNameResolver;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.TransportIndexAction;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.VersionConflictException;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

public final class ReindexAction {

    public static final String ACTION_NAME = "indices:data/write/reindex";

    private final RelocationAwareRouter router;
    private final TransportIndexAction indexAction;
    private final BulkByScrollExecutor executor;
    private final TaskManager taskManager;
    private final ByQuerySearchHooks.QueryConverter queryConverter;
    private final ThrottleRegistry throttleRegistry;

    public ReindexAction(RelocationAwareRouter router, SearchCoordinator searchCoordinator, TaskManager taskManager,
                          ThrottleRegistry throttleRegistry) {
        this(router, ByQuerySearchHooks.defaults(searchCoordinator), taskManager, throttleRegistry);
    }

    public ReindexAction(RelocationAwareRouter router, ByQuerySearchHooks hooks, TaskManager taskManager,
                          ThrottleRegistry throttleRegistry) {
        this.queryConverter = hooks.queryConverter();
        this.router = router;
        this.indexAction = new TransportIndexAction(router);
        this.executor = new BulkByScrollExecutor(hooks.searcher(), () -> router.clusterStateSupplier().getClusterState().getRoutingTable());
        this.taskManager = taskManager;
        this.throttleRegistry = throttleRegistry;
    }

    public BulkByScrollResponse execute(String sourceIndex, Map<String, Object> sourceQueryClause, String destIndex,
                                         ByQueryOptions options) throws IOException {
        return execute(sourceIndex, sourceQueryClause, destIndex, null, options);
    }

    public BulkByScrollResponse execute(String sourceIndex, Map<String, Object> sourceQueryClause, String destIndex,
                                         UnaryOperator<Map<String, Object>> transform, ByQueryOptions options) throws IOException {
        UnaryOperator<Map<String, Object>> effectiveTransform = transform != null ? transform : UnaryOperator.identity();
        IndexNameResolver.Resolution sourceResolution = router.resolveIndex(sourceIndex);
        String resolvedSourceIndex = sourceResolution.index();
        IndexNameResolver.Resolution destResolution = router.resolveIndex(destIndex);
        String resolvedDestIndex = destResolution.index();
        Query query = queryConverter.convert(resolvedSourceIndex, sourceQueryClause);

        Task task = taskManager.register("transport", ACTION_NAME,
            "reindex [" + resolvedSourceIndex + "] -> [" + resolvedDestIndex + "]", true, null);
        ThrottleController throttle = new ThrottleController(options.requestsPerSecond());
        throttleRegistry.put(task.id(), throttle);
        try {
            BulkByScrollResponse response = executor.run(resolvedSourceIndex, query, options, task, throttle, false, null,
                hit -> reindexOne(resolvedDestIndex, hit, effectiveTransform, options));
            task.complete(response);
            return response;
        } catch (RuntimeException | IOException e) {
            task.completeExceptionally(e);
            throw e;
        } finally {
            throttleRegistry.remove(task.id());
            taskManager.unregister(task.id());
        }
    }

    private BulkByScrollExecutor.HandlerResult reindexOne(String destIndex,
                                                            com.naqqa.elasticsearch.action.search.SearchResponse.Hit hit,
                                                            UnaryOperator<Map<String, Object>> transform,
                                                            ByQueryOptions options) throws IOException {
        if (hit.id() == null || !router.replicationGroups().containsKey(router.resolveShardId(destIndex, hit.id(), null))) {
            return BulkByScrollExecutor.HandlerResult.skipped();
        }
        Map<String, Object> source = hit.source() == null ? new LinkedHashMap<>() : hit.sourceAsMap();
        Map<String, Object> transformed = transform.apply(new LinkedHashMap<>(source));
        DocumentActionService.IndexRequest request = new DocumentActionService.IndexRequest(destIndex, hit.id(),
            transformed, null, null, null, null, null, options.opType(), options.refresh(), null);
        try {
            DocumentActionService.IndexResult result = indexAction.execute(request);
            return BulkByScrollExecutor.HandlerResult.of(result.created()
                ? BulkByScrollExecutor.Kind.CREATED : BulkByScrollExecutor.Kind.UPDATED);
        } catch (VersionConflictException e) {
            return BulkByScrollExecutor.HandlerResult.conflict();
        }
    }
}
