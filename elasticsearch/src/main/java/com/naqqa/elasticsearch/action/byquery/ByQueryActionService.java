package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

public final class ByQueryActionService {

    private final DeleteByQueryAction deleteByQueryAction;
    private final UpdateByQueryAction updateByQueryAction;
    private final ReindexAction reindexAction;
    private final TermVectorsAction termVectorsAction;
    private final MultiTermVectorsAction multiTermVectorsAction;
    private final RethrottleAction rethrottleAction;
    private final ScriptService scriptService;
    private final ExecutorService executor;

    public ByQueryActionService(RelocationAwareRouter router, SearchCoordinator searchCoordinator, TaskManager taskManager) {
        this(router, searchCoordinator, taskManager, ScriptService.defaults());
    }

    public ByQueryActionService(RelocationAwareRouter router, SearchCoordinator searchCoordinator, TaskManager taskManager,
                                 ScriptService scriptService) {
        this(router, ByQuerySearchHooks.defaults(searchCoordinator), taskManager, scriptService);
    }

    public ByQueryActionService(RelocationAwareRouter router, ByQuerySearchHooks hooks, TaskManager taskManager,
                                 ScriptService scriptService) {
        ThrottleRegistry throttleRegistry = new ThrottleRegistry();
        this.deleteByQueryAction = new DeleteByQueryAction(router, hooks, taskManager, throttleRegistry);
        this.updateByQueryAction = new UpdateByQueryAction(router, hooks, taskManager, throttleRegistry);
        this.reindexAction = new ReindexAction(router, hooks, taskManager, throttleRegistry);
        this.termVectorsAction = new TermVectorsAction(router);
        this.multiTermVectorsAction = new MultiTermVectorsAction(router);
        this.rethrottleAction = new RethrottleAction(taskManager, throttleRegistry);
        this.scriptService = scriptService;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "byquery-action");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Map<String, Object>> deleteByQuery(String index, Map<String, Object> requestBody,
                                                                  Map<String, String> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> queryClause = extractQuery(requestBody);
                ByQueryOptions options = ByQueryOptions.fromParams(params);
                return deleteByQueryAction.execute(index, queryClause, options).toMap();
            } catch (IOException e) {
                throw new RestApiException(500, e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<Map<String, Object>> updateByQuery(String index, Map<String, Object> requestBody,
                                                                  Map<String, String> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> queryClause = extractQuery(requestBody);
                ByQueryOptions options = ByQueryOptions.fromParams(params);
                UnaryOperator<Map<String, Object>> transform = buildTransform(requestBody == null ? null : requestBody.get("script"));
                return updateByQueryAction.execute(index, queryClause, transform, options).toMap();
            } catch (IOException e) {
                throw new RestApiException(500, e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody) {
        return reindex(requestBody, Map.of());
    }

    public CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody, Map<String, String> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (requestBody == null) {
                    throw new RestApiException(400, "_reindex requires a request body with [source] and [dest]");
                }
                Map<String, Object> source = asMap(requestBody.get("source"));
                Map<String, Object> dest = asMap(requestBody.get("dest"));
                if (source == null || dest == null) {
                    throw new RestApiException(400, "_reindex requires both [source] and [dest]");
                }
                String sourceIndex = String.valueOf(source.get("index"));
                String destIndex = String.valueOf(dest.get("index"));
                Map<String, Object> queryClause = asMap(source.get("query"));
                String opType = dest.get("op_type") != null ? String.valueOf(dest.get("op_type")) : ByQueryOptions.DEFAULT.opType();
                ByQueryOptions fromParams = ByQueryOptions.fromParams(params);
                long maxDocs = requestBody.get("max_docs") != null ? Long.parseLong(String.valueOf(requestBody.get("max_docs")))
                    : fromParams.maxDocs();
                boolean abortOnConflict = requestBody.get("conflicts") != null
                    ? !"proceed".equalsIgnoreCase(String.valueOf(requestBody.get("conflicts"))) : fromParams.abortOnConflict();
                int batchSize = source.get("size") != null ? Integer.parseInt(String.valueOf(source.get("size"))) : fromParams.batchSize();
                ByQueryOptions options = new ByQueryOptions(batchSize, fromParams.requestsPerSecond(), maxDocs,
                    fromParams.timeoutMillis(), fromParams.preference(), "false", fromParams.retryOnConflict(), abortOnConflict,
                    dest.get("op_type") != null ? opType : fromParams.opType());
                UnaryOperator<Map<String, Object>> transform = buildTransform(requestBody.get("script"));
                return reindexAction.execute(sourceIndex, queryClause, destIndex, transform, options).toMap();
            } catch (IOException e) {
                throw new RestApiException(500, e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<Map<String, Object>> termVectors(String index, String id, Map<String, Object> requestBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> fields = extractFields(requestBody);
                return termVectorsAction.get(index, id, fields);
            } catch (IOException e) {
                throw new RestApiException(500, e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<Map<String, Object>> multiTermVectors(Map<String, Object> requestBody) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<MultiTermVectorsAction.DocSpec> specs = parseDocSpecs(requestBody);
                return multiTermVectorsAction.get(specs);
            } catch (IOException e) {
                throw new RestApiException(500, e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<Map<String, Object>> rethrottle(String taskId, Double requestsPerSecond) {
        return CompletableFuture.supplyAsync(() ->
            rethrottleAction.rethrottle(taskId, requestsPerSecond == null ? -1d : requestsPerSecond), executor);
    }

    private UnaryOperator<Map<String, Object>> buildTransform(Object scriptObj) {
        if (scriptObj == null) {
            return null;
        }
        Script script = Script.parse(scriptObj);
        return source -> {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("_source", source);
            ctx.put("op", "index");
            scriptService.execute(script, ScriptContext.UPDATE_BY_QUERY, Map.of("ctx", ctx));
            return asMap(ctx.get("_source"));
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractQuery(Map<String, Object> requestBody) {
        if (requestBody == null) {
            return null;
        }
        return asMap(requestBody.get("query"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
            return result;
        }
        throw new RestApiException(400, "expected a JSON object but got [" + value + "]");
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractFields(Map<String, Object> requestBody) {
        if (requestBody == null || requestBody.get("fields") == null) {
            return null;
        }
        Object raw = requestBody.get("fields");
        List<String> fields = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                fields.add(String.valueOf(o));
            }
        } else {
            fields.add(String.valueOf(raw));
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static List<MultiTermVectorsAction.DocSpec> parseDocSpecs(Map<String, Object> requestBody) {
        List<MultiTermVectorsAction.DocSpec> specs = new ArrayList<>();
        if (requestBody == null) {
            return specs;
        }
        String defaultIndex = requestBody.get("index") != null ? String.valueOf(requestBody.get("index")) : null;
        List<String> defaultFields = extractFields(requestBody);
        Object docs = requestBody.get("docs");
        if (docs instanceof List<?> docList) {
            for (Object docObj : docList) {
                Map<String, Object> doc = asMap(docObj);
                String docIndex = doc.get("_index") != null ? String.valueOf(doc.get("_index")) : defaultIndex;
                String docId = String.valueOf(doc.get("_id"));
                List<String> docFields = extractFields(doc);
                specs.add(new MultiTermVectorsAction.DocSpec(docIndex, docId, docFields != null ? docFields : defaultFields));
            }
            return specs;
        }
        Object ids = requestBody.get("ids");
        if (ids instanceof List<?> idList) {
            for (Object idObj : idList) {
                specs.add(new MultiTermVectorsAction.DocSpec(defaultIndex, String.valueOf(idObj), defaultFields));
            }
        }
        return specs;
    }
}
