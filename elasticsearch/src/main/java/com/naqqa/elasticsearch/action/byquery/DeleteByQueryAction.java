package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.write.IndexNameResolver;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.action.write.TransportDeleteAction;
import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.Map;

public final class DeleteByQueryAction {

    public static final String ACTION_NAME = "indices:data/write/delete/byquery";

    private final RelocationAwareRouter router;
    private final TransportDeleteAction deleteAction;
    private final BulkByScrollExecutor executor;
    private final TaskManager taskManager;
    private final ThrottleRegistry throttleRegistry;

    public DeleteByQueryAction(RelocationAwareRouter router, SearchCoordinator searchCoordinator,
                                TaskManager taskManager, ThrottleRegistry throttleRegistry) {
        this.router = router;
        this.deleteAction = new TransportDeleteAction(router);
        this.executor = new BulkByScrollExecutor(searchCoordinator, () -> router.clusterStateSupplier().getClusterState().getRoutingTable());
        this.taskManager = taskManager;
        this.throttleRegistry = throttleRegistry;
    }

    public BulkByScrollResponse execute(String index, Map<String, Object> queryClause, ByQueryOptions options) throws IOException {
        IndexNameResolver.Resolution resolution = router.resolveIndex(index);
        String resolvedIndex = resolution.index();
        Query query = QueryClauseConverter.convert(queryClause);

        Task task = taskManager.register("transport", ACTION_NAME, "delete-by-query [" + resolvedIndex + "]", true, null);
        ThrottleController throttle = new ThrottleController(options.requestsPerSecond());
        throttleRegistry.put(task.id(), throttle);
        try {
            BulkByScrollResponse response = executor.run(resolvedIndex, query, options, task, throttle, true,
                () -> ShardRefresher.refreshIndex(router, resolvedIndex),
                hit -> deleteOne(resolvedIndex, hit.id(), options));
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

    private BulkByScrollExecutor.HandlerResult deleteOne(String index, String id, ByQueryOptions options) throws IOException {
        DocumentActionService.DeleteRequest request = new DocumentActionService.DeleteRequest(index, id, null, null,
            null, null, null, options.refresh());
        DocumentActionService.DeleteResult result = deleteAction.execute(request);
        if (!result.found()) {
            return BulkByScrollExecutor.HandlerResult.of(BulkByScrollExecutor.Kind.NOOP);
        }
        return BulkByScrollExecutor.HandlerResult.of(BulkByScrollExecutor.Kind.DELETED);
    }
}
