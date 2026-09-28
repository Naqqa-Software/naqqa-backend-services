package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.write.IndexNameResolver;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.TransportUpdateAction;
import com.naqqa.elasticsearch.action.write.UpdateScriptExecutor;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.DocumentMissingException;
import com.naqqa.elasticsearch.rest.support.VersionConflictException;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public final class UpdateByQueryAction {

    public static final String ACTION_NAME = "indices:data/write/update/byquery";

    private final RelocationAwareRouter router;
    private final BulkByScrollExecutor executor;
    private final TaskManager taskManager;
    private final ByQuerySearchHooks.QueryConverter queryConverter;
    private final ThrottleRegistry throttleRegistry;

    public UpdateByQueryAction(RelocationAwareRouter router, SearchCoordinator searchCoordinator,
                                TaskManager taskManager, ThrottleRegistry throttleRegistry) {
        this(router, ByQuerySearchHooks.defaults(searchCoordinator), taskManager, throttleRegistry);
    }

    public UpdateByQueryAction(RelocationAwareRouter router, ByQuerySearchHooks hooks,
                                TaskManager taskManager, ThrottleRegistry throttleRegistry) {
        this.queryConverter = hooks.queryConverter();
        this.router = router;
        this.executor = new BulkByScrollExecutor(hooks.searcher(), () -> router.clusterStateSupplier().getClusterState().getRoutingTable());
        this.taskManager = taskManager;
        this.throttleRegistry = throttleRegistry;
    }

    public BulkByScrollResponse execute(String index, Map<String, Object> queryClause,
                                         UnaryOperator<Map<String, Object>> scriptOrNoop, ByQueryOptions options) throws IOException {
        UnaryOperator<Map<String, Object>> transform = scriptOrNoop != null ? scriptOrNoop : UnaryOperator.identity();
        IndexNameResolver.Resolution resolution = router.resolveIndex(index);
        String resolvedIndex = resolution.index();
        Query query = queryConverter.convert(resolvedIndex, queryClause);

        AtomicLong retriesAccumulated = new AtomicLong();
        long[] perDocAttempts = new long[1];
        UpdateScriptExecutor scriptExecutor = (scriptDefinition, currentSource) -> {
            perDocAttempts[0]++;
            return transform.apply(currentSource);
        };
        TransportUpdateAction updateAction = new TransportUpdateAction(router, scriptExecutor);

        Task task = taskManager.register("transport", ACTION_NAME, "update-by-query [" + resolvedIndex + "]", true, null);
        ThrottleController throttle = new ThrottleController(options.requestsPerSecond());
        throttleRegistry.put(task.id(), throttle);
        try {
            BulkByScrollResponse response = executor.run(resolvedIndex, query, options, task, throttle, false, null,
                hit -> updateOne(updateAction, resolvedIndex, hit.id(), options, perDocAttempts, retriesAccumulated));
            response = response.withRetries(retriesAccumulated.get());
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

    private BulkByScrollExecutor.HandlerResult updateOne(TransportUpdateAction updateAction, String index, String id,
                                                           ByQueryOptions options, long[] perDocAttempts,
                                                           AtomicLong retriesAccumulated) throws IOException {
        perDocAttempts[0] = 0;
        if (id == null || !router.replicationGroups().containsKey(router.resolveShardId(index, id, null))) {
            return BulkByScrollExecutor.HandlerResult.skipped();
        }
        DocumentActionService.UpdateRequest request = new DocumentActionService.UpdateRequest(index, id, null, null,
            false, true, options.retryOnConflict(), Map.of("source", "noop"), null, null, options.refresh(), false);
        try {
            DocumentActionService.UpdateResult result = updateAction.execute(request);
            if (result.noop()) {
                return BulkByScrollExecutor.HandlerResult.of(BulkByScrollExecutor.Kind.NOOP);
            }
            return BulkByScrollExecutor.HandlerResult.of("created".equals(result.result())
                ? BulkByScrollExecutor.Kind.CREATED : BulkByScrollExecutor.Kind.UPDATED);
        } catch (VersionConflictException e) {
            return BulkByScrollExecutor.HandlerResult.conflict();
        } catch (DocumentMissingException e) {
            return BulkByScrollExecutor.HandlerResult.of(BulkByScrollExecutor.Kind.NOOP);
        } finally {
            if (perDocAttempts[0] > 1) {
                retriesAccumulated.addAndGet(perDocAttempts[0] - 1);
            }
        }
    }
}
