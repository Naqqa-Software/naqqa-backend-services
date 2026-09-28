package com.naqqa.elasticsearch.node.action;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.monitor.NodeCounters;
import com.naqqa.elasticsearch.node.search.MultiShardSearcher;
import com.naqqa.elasticsearch.node.search.SearchEngine;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.search.SearchActionService;
import com.naqqa.elasticsearch.rest.support.DocumentMissingException;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.script.StoredScriptSource;
import com.naqqa.elasticsearch.search.advanced.async.AsyncSearchService;
import com.naqqa.elasticsearch.search.advanced.pagination.FieldDoc;
import com.naqqa.elasticsearch.search.advanced.pagination.PitContext;
import com.naqqa.elasticsearch.search.advanced.pagination.PitService;
import com.naqqa.elasticsearch.search.advanced.pagination.ScrollContext;
import com.naqqa.elasticsearch.search.advanced.pagination.ScrollService;
import com.naqqa.elasticsearch.search.advanced.requests.ExplainRenderer;
import com.naqqa.elasticsearch.search.advanced.requests.RankEval;
import com.naqqa.elasticsearch.search.advanced.requests.SearchTemplate;
import com.naqqa.elasticsearch.search.advanced.requests.TermsEnumLister;
import com.naqqa.elasticsearch.search.advanced.requests.ValidateQuery;
import com.naqqa.elasticsearch.search.execution.LeafReader;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public final class NodeSearchActionService implements SearchActionService, AutoCloseable {

    private record ScrollState(MultiShardSearcher searcher, SearchEngine.ParsedSearch parsed) {
    }

    private record PitState(List<String> indices, Map<ShardId, String> shardPits) {
    }

    private final SearchEngine engine;
    private final ScriptService scriptService;
    private final ExecutorService executor;
    private final NodeCounters counters;
    private final ScrollService scrollService = new ScrollService();
    private final PitService pitService = new PitService();
    private final AsyncSearchService asyncSearchService = new AsyncSearchService();
    private final Map<String, ScrollState> scrolls = new ConcurrentHashMap<>();
    private final Map<String, PitState> pits = new ConcurrentHashMap<>();
    private volatile java.util.function.Function<List<String>, Map<String, Object>> aliasFilters = expressions -> null;

    public void setAliasFilters(java.util.function.Function<List<String>, Map<String, Object>> aliasFilters) {
        this.aliasFilters = aliasFilters;
    }

    private Map<String, Object> withAliasFilter(List<String> indices, Map<String, Object> body) {
        Map<String, Object> filter = aliasFilters.apply(indices);
        if (filter == null || filter.isEmpty()) {
            return body;
        }
        Map<String, Object> out = new LinkedHashMap<>(body);
        Object query = body.get("query");
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(query == null ? Map.of("match_all", Map.of()) : query));
        bool.put("filter", List.of(filter));
        out.put("query", Map.of("bool", bool));
        return out;
    }

    public NodeSearchActionService(SearchEngine engine, ScriptService scriptService, ExecutorService executor, NodeCounters counters) {
        this.engine = engine;
        this.scriptService = scriptService;
        this.executor = executor;
        this.counters = counters;
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private static RuntimeException wrap(Exception e) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RestApiException(500, e.getMessage(), e);
    }

    @Override
    public CompletableFuture<Map<String, Object>> search(List<String> indices, Map<String, Object> requestBody,
                                                          Map<String, String> params) {
        return async(() -> doSearch(indices, requestBody, params));
    }

    private Map<String, Object> doSearch(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        try {
            Map<String, Object> raw = requestBody == null ? new LinkedHashMap<>() : requestBody;
            Map<String, Object> pit = SettingsMaps.asMap(raw.get("pit"));
            if (pit != null && pit.get("id") != null) {
                return searchWithPit(String.valueOf(pit.get("id")), raw, params);
            }
            List<String> resolved = engine.resolve(indices, params);
            if (params != null && params.get("scroll") != null) {
                Map<String, Object> body = withAliasFilter(indices, raw);
                SearchEngine.ParsedSearch parsed = engine.parse(resolved, body, params);
                return openScroll(resolved, parsed, params.get("scroll"));
            }
            return engine.search(resolved, raw, params, aliasFilters.apply(indices));
        } catch (IOException e) {
            throw wrap(e);
        }
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> multiSearch(List<MsearchItem> items) {
        return async(() -> {
            List<Map<String, Object>> responses = new ArrayList<>();
            for (MsearchItem item : items) {
                try {
                    Map<String, String> params = new LinkedHashMap<>();
                    if (item.header() != null) {
                        for (Map.Entry<String, Object> e : item.header().entrySet()) {
                            if (!"index".equals(e.getKey()) && e.getValue() != null) {
                                params.put(e.getKey(), String.valueOf(e.getValue()));
                            }
                        }
                    }
                    Map<String, Object> response = doSearch(item.indices(), item.body(), params);
                    Map<String, Object> withStatus = new LinkedHashMap<>(response);
                    withStatus.put("status", 200);
                    responses.add(withStatus);
                } catch (RuntimeException e) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    int status = e instanceof RestApiException rae ? rae.restStatus() : 500;
                    error.put("error", Map.of("type", e.getClass().getSimpleName(), "reason", String.valueOf(e.getMessage())));
                    error.put("status", status);
                    responses.add(error);
                }
            }
            return responses;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> count(List<String> indices, Map<String, Object> requestBody, Map<String, String> params) {
        return async(() -> {
            List<String> resolved = engine.resolve(indices, params);
            Map<String, Object> body = withAliasFilter(indices, requestBody == null ? new LinkedHashMap<>() : requestBody);
            Map<String, Object> countBody = new LinkedHashMap<>();
            if (body.get("query") != null) {
                countBody.put("query", body.get("query"));
            }
            Query query = engine.parse(resolved, countBody, paramsWithoutPaging(params)).query();
            Map<ShardId, IndexShard> shards = engine.shardsFor(resolved);
            long count = 0;
            try (MultiShardSearcher searcher = MultiShardSearcher.open(shards)) {
                count = searcher.searcher().count(query);
            } catch (IOException e) {
                throw wrap(e);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", count);
            out.put("_shards", Map.of("total", shards.size(), "successful", shards.size(), "skipped", 0, "failed", 0));
            return out;
        });
    }

    private static Map<String, String> paramsWithoutPaging(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null) {
            out.putAll(params);
        }
        out.remove("from");
        out.remove("size");
        out.remove("sort");
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> explain(String index, String id, Map<String, Object> requestBody,
                                                          Map<String, String> params) {
        return async(() -> {
            List<String> resolved = engine.resolve(List.of(index), params);
            if (resolved.isEmpty()) {
                throw new DocumentMissingException(index, id);
            }
            String concrete = resolved.get(0);
            Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
            Query query = engine.toQuery(List.of(concrete), SettingsMaps.asMap(body.get("query")));
            try (MultiShardSearcher searcher = MultiShardSearcher.open(engine.shardsFor(List.of(concrete)))) {
                Integer doc = searcher.findDoc(concrete, id);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("_index", concrete);
                out.put("_id", id);
                if (doc == null) {
                    out.put("matched", false);
                    return out;
                }
                Explanation explanation = searcher.searcher().explain(query, doc);
                out.put("matched", explanation.isMatch());
                out.put("explanation", ExplainRenderer.render(explanation));
                return out;
            } catch (IOException e) {
                throw wrap(e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> validateQuery(List<String> indices, Map<String, Object> requestBody,
                                                                Map<String, String> params) {
        return async(() -> {
            List<String> resolved = engine.resolve(indices, params);
            Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
            Map<String, Object> query = SettingsMaps.asMap(body.get("query"));
            if (query == null) {
                query = Map.of("match_all", Map.of());
            }
            Map<ShardId, IndexShard> shards = engine.shardsFor(resolved);
            ValidateQuery.Result result;
            try (MultiShardSearcher searcher = MultiShardSearcher.open(shards)) {
                result = ValidateQuery.validate(query, searcher.searcher());
            } catch (IOException e) {
                throw wrap(e);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_shards", Map.of("total", shards.size(), "successful", shards.size(), "failed", 0));
            out.put("valid", result.valid());
            boolean explain = params != null && "true".equals(params.get("explain"));
            if (explain || !result.valid()) {
                List<Object> explanations = new ArrayList<>();
                for (String index : resolved) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("index", index);
                    e.put("valid", result.valid());
                    if (result.valid()) {
                        e.put("explanation", result.explanation());
                    } else {
                        e.put("error", result.error());
                    }
                    explanations.add(e);
                }
                out.put("explanations", explanations);
                if (!result.valid()) {
                    out.put("error", result.error());
                }
            }
            return out;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> fieldCaps(List<String> indices, Map<String, String> params) {
        return async(() -> {
            List<String> resolved = engine.resolve(indices, params);
            List<String> fieldPatterns = SettingsMaps.asStringList(params == null ? null : params.get("fields"));
            if (fieldPatterns.isEmpty()) {
                fieldPatterns = List.of("*");
            }
            Map<String, Map<String, List<String>>> byFieldAndType = new TreeMap<>();
            for (String index : resolved) {
                IndexService service = engine.indicesService().indexService(index);
                if (service == null || service.mapperService().documentMapper() == null) {
                    continue;
                }
                Map<String, Object> mapping = SettingsMaps.asMap(service.mapperService().documentMapper().mapping().toMapping().toJava());
                Map<String, String> types = new LinkedHashMap<>();
                collectFieldTypes("", mapping == null ? Map.of() : mapping, types);
                for (Map.Entry<String, String> e : types.entrySet()) {
                    boolean matches = false;
                    for (String pattern : fieldPatterns) {
                        if (com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(pattern, e.getKey())) {
                            matches = true;
                            break;
                        }
                    }
                    if (matches) {
                        byFieldAndType.computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                            .computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(index);
                    }
                }
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, List<String>>> e : byFieldAndType.entrySet()) {
                Map<String, Object> perType = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> t : e.getValue().entrySet()) {
                    Map<String, Object> cap = new LinkedHashMap<>();
                    cap.put("type", t.getKey());
                    cap.put("metadata_field", false);
                    cap.put("searchable", !"object".equals(t.getKey()));
                    cap.put("aggregatable", !"text".equals(t.getKey()) && !"object".equals(t.getKey()));
                    if (e.getValue().size() > 1) {
                        cap.put("indices", t.getValue());
                    }
                    perType.put(t.getKey(), cap);
                }
                fields.put(e.getKey(), perType);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("indices", resolved);
            out.put("fields", fields);
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private static void collectFieldTypes(String prefix, Map<String, Object> mapping, Map<String, String> out) {
        Object props = mapping.get("properties");
        if (!(props instanceof Map<?, ?> properties)) {
            return;
        }
        for (Map.Entry<?, ?> e : properties.entrySet()) {
            String name = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            Map<String, Object> def = SettingsMaps.asMap(e.getValue());
            if (def == null) {
                continue;
            }
            Object type = def.get("type");
            if (type != null) {
                out.put(name, String.valueOf(type));
            } else if (def.containsKey("properties")) {
                out.put(name, "object");
            }
            if (def.containsKey("properties")) {
                collectFieldTypes(name, def, out);
            }
            Map<String, Object> multi = SettingsMaps.asMap(def.get("fields"));
            if (multi != null) {
                for (Map.Entry<String, Object> m : multi.entrySet()) {
                    Map<String, Object> sub = SettingsMaps.asMap(m.getValue());
                    if (sub != null && sub.get("type") != null) {
                        out.put(name + "." + m.getKey(), String.valueOf(sub.get("type")));
                    }
                }
            }
        }
    }

    private static long parseKeepAlive(String value, long defaultMillis) {
        if (value == null) {
            return defaultMillis;
        }
        return com.naqqa.elasticsearch.common.unit.TimeValue.parseTimeValue(value, "keep_alive").millis();
    }

    private Map<String, Object> openScroll(List<String> indices, SearchEngine.ParsedSearch parsed, String keepAlive) throws IOException {
        long start = counters.scroll.start();
        boolean ok = false;
        try {
            MultiShardSearcher searcher = MultiShardSearcher.open(engine.shardsFor(indices));
            ScrollContext.ScrollPage page;
            try {
                page = scrollService.openFrom(searcher.searcher(), parsed.query(),
                    parsed.sort() == null ? com.naqqa.elasticsearch.search.execution.Sort.RELEVANCE : parsed.sort(), Math.max(1, parsed.size()),
                    parseKeepAlive(keepAlive, 60_000L));
            } catch (IOException | RuntimeException e) {
                searcher.close();
                throw e;
            }
            scrolls.put(page.scrollId(), new ScrollState(searcher, parsed));
            Map<String, Object> out = renderScrollPage(page, searcher, parsed);
            ok = true;
            return out;
        } finally {
            counters.scroll.end(start, ok);
        }
    }

    private Map<String, Object> renderScrollPage(ScrollContext.ScrollPage page, MultiShardSearcher searcher,
                                                 SearchEngine.ParsedSearch parsed) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_scroll_id", page.scrollId());
        out.put("took", 0);
        out.put("timed_out", false);
        int shards = searcher.engineSearchers().size();
        out.put("_shards", Map.of("total", shards, "successful", shards, "skipped", 0, "failed", 0));
        Map<String, Object> hits = new LinkedHashMap<>();
        hits.put("total", Map.of("value", page.totalHits().value(), "relation",
            page.totalHits().relation().name().equals("EQUAL_TO") ? "eq" : "gte"));
        List<Object> list = new ArrayList<>();
        Float maxScore = null;
        for (FieldDoc fd : page.hits()) {
            StoredDocCodec.Decoded decoded = searcher.fetch(fd.doc());
            ShardId shardId = searcher.shardOf(fd.doc());
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("_index", shardId == null ? null : shardId.index());
            h.put("_id", decoded == null ? null : decoded.id());
            h.put("_score", parsed.sort() == null ? fd.score() : null);
            if (parsed.sort() == null && (maxScore == null || fd.score() > maxScore)) {
                maxScore = fd.score();
            }
            if (parsed.fetchSource() && decoded != null && decoded.source() != null) {
                h.put("_source", SearchEngine.sourceOf(decoded.source()));
            }
            if (fd.fields() != null && parsed.sort() != null) {
                List<Object> sortValues = new ArrayList<>();
                for (Object v : fd.fields()) {
                    sortValues.add(v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : v);
                }
                h.put("sort", sortValues);
            }
            list.add(h);
        }
        hits.put("max_score", maxScore);
        hits.put("hits", list);
        out.put("hits", hits);
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> scroll(String scrollId, String scrollTtl) {
        return async(() -> {
            ScrollState state = scrolls.get(scrollId);
            if (state == null) {
                throw new RestApiException(404, "No search context found for id [" + scrollId + "]");
            }
            long start = counters.scroll.start();
            boolean ok = false;
            try {
                ScrollContext.ScrollPage page = scrollService.next(scrollId, parseKeepAlive(scrollTtl, 60_000L));
                Map<String, Object> out = renderScrollPage(page, state.searcher(), state.parsed());
                ok = true;
                return out;
            } catch (IllegalArgumentException e) {
                releaseScroll(scrollId);
                throw new RestApiException(404, e.getMessage());
            } catch (IOException e) {
                throw wrap(e);
            } finally {
                counters.scroll.end(start, ok);
            }
        });
    }

    private boolean releaseScroll(String scrollId) {
        boolean cleared = scrollService.clear(scrollId);
        ScrollState state = scrolls.remove(scrollId);
        if (state != null) {
            try {
                state.searcher().close();
            } catch (IOException ignored) {
            }
            return true;
        }
        return cleared;
    }

    @Override
    public CompletableFuture<Map<String, Object>> clearScroll(List<String> scrollIds) {
        return async(() -> {
            int freed = 0;
            List<String> ids = scrollIds == null || scrollIds.isEmpty() || scrollIds.contains("_all")
                ? new ArrayList<>(scrolls.keySet()) : scrollIds;
            for (String id : ids) {
                if (releaseScroll(id)) {
                    freed++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("succeeded", true);
            out.put("num_freed", freed);
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> openPointInTime(List<String> indices, Map<String, String> params) {
        return async(() -> {
            List<String> resolved = engine.resolve(indices, params);
            long keepAlive = parseKeepAlive(params == null ? null : params.get("keep_alive"), 60_000L);
            Map<ShardId, String> shardPits = new LinkedHashMap<>();
            try {
                for (Map.Entry<ShardId, IndexShard> e : engine.shardsFor(resolved).entrySet()) {
                    shardPits.put(e.getKey(), pitService.open(e.getValue(), keepAlive));
                }
            } catch (IOException e) {
                for (String pitId : shardPits.values()) {
                    pitService.close(pitId);
                }
                throw wrap(e);
            }
            StringBuilder raw = new StringBuilder();
            for (Map.Entry<ShardId, String> e : shardPits.entrySet()) {
                raw.append(e.getKey().index()).append('\u0001').append(e.getKey().id()).append('\u0001').append(e.getValue()).append('\u0002');
            }
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
            pits.put(id, new PitState(resolved, shardPits));
            return Map.of("id", id);
        });
    }

    private Map<String, Object> searchWithPit(String pitId, Map<String, Object> body, Map<String, String> params) throws IOException {
        PitState state = pits.get(pitId);
        if (state == null) {
            throw new RestApiException(404, "No search context found for id [" + pitId + "]");
        }
        Map<ShardId, EngineSearcher> searchers = new LinkedHashMap<>();
        for (Map.Entry<ShardId, String> e : state.shardPits().entrySet()) {
            PitContext ctx;
            try {
                ctx = pitService.get(e.getValue());
            } catch (RuntimeException ex) {
                pits.remove(pitId);
                throw new RestApiException(404, "point in time [" + pitId + "] has expired or was closed");
            }
            searchers.put(e.getKey(), ctx.engineSearcher());
        }
        Map<String, Object> keepAliveSpec = SettingsMaps.asMap(body.get("pit"));
        if (keepAliveSpec != null && keepAliveSpec.get("keep_alive") != null) {
            long keepAlive = parseKeepAlive(String.valueOf(keepAliveSpec.get("keep_alive")), 60_000L);
            for (String shardPit : state.shardPits().values()) {
                pitService.keepAlive(shardPit, keepAlive);
            }
        }
        Map<String, Object> bodyWithoutPit = new LinkedHashMap<>(body);
        bodyWithoutPit.remove("pit");
        Map<String, Object> rendered = new LinkedHashMap<>(engine.searchOnSearchers(state.indices(), searchers, bodyWithoutPit, params));
        rendered.put("pit_id", pitId);
        return rendered;
    }

    @Override
    public CompletableFuture<Map<String, Object>> closePointInTime(String pitId) {
        return async(() -> {
            PitState state = pits.remove(pitId);
            int freed = 0;
            if (state != null) {
                for (String shardPit : state.shardPits().values()) {
                    if (pitService.close(shardPit)) {
                        freed++;
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("succeeded", state != null);
            out.put("num_freed", freed);
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> submitAsyncSearch(List<String> indices, Map<String, Object> requestBody,
                                                                    Map<String, String> params) {
        return async(() -> {
            long wait = parseKeepAlive(params == null ? null : params.get("wait_for_completion_timeout"), 1_000L);
            long keepAlive = parseKeepAlive(params == null ? null : params.get("keep_alive"), 5 * 24 * 3_600_000L);
            boolean keepOnCompletion = params != null && "true".equals(params.get("keep_on_completion"));
            Map<String, String> searchParams = new LinkedHashMap<>();
            if (params != null) {
                searchParams.putAll(params);
            }
            searchParams.remove("wait_for_completion_timeout");
            searchParams.remove("keep_alive");
            searchParams.remove("keep_on_completion");
            AsyncSearchService.Response<Map<String, Object>> response = asyncSearchService.submit(
                () -> doSearch(indices, requestBody, searchParams), wait, keepAlive, keepOnCompletion);
            return renderAsync(response);
        });
    }

    private static Map<String, Object> renderAsync(AsyncSearchService.Response<Map<String, Object>> response) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response.id() != null) {
            out.put("id", response.id());
        }
        out.put("is_partial", response.isPartial());
        out.put("is_running", response.isRunning());
        if (response.result() != null) {
            out.put("response", response.result());
        }
        if (response.error() != null) {
            Throwable error = response.error() instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : response.error();
            out.put("error", Map.of("type", error.getClass().getSimpleName(), "reason", String.valueOf(error.getMessage())));
        }
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAsyncSearch(String id, Map<String, String> params) {
        return async(() -> {
            AsyncSearchService.Response<Map<String, Object>> response;
            try {
                response = asyncSearchService.poll(id);
            } catch (RuntimeException e) {
                throw new RestApiException(404, "async search [" + id + "] not found");
            }
            if (response == null) {
                throw new RestApiException(404, "async search [" + id + "] not found");
            }
            return renderAsync(response);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> deleteAsyncSearch(String id) {
        return async(() -> {
            if (!asyncSearchService.delete(id)) {
                throw new RestApiException(404, "async search [" + id + "] not found");
            }
            return Map.of("acknowledged", true);
        });
    }

    @SuppressWarnings("unchecked")
    private String templateSource(Map<String, Object> body) {
        Object source = body.get("source");
        if (source == null && body.get("id") != null) {
            String id = String.valueOf(body.get("id"));
            StoredScriptSource stored = scriptService.getStoredScript(id);
            if (stored == null) {
                throw new RestApiException(404, "unable to find script [" + id + "]");
            }
            return stored.source();
        }
        if (source instanceof Map<?, ?> m) {
            return JsonValue.wrap(m).toString();
        }
        if (source == null) {
            throw new RestApiException(400, "search template requires [source] or [id]");
        }
        return String.valueOf(source);
    }

    private Map<String, Object> renderTemplateBody(Map<String, Object> body) {
        Map<String, Object> params = SettingsMaps.asMap(body.get("params"));
        return SearchTemplate.renderToMap(templateSource(body), params == null ? Map.of() : params);
    }

    @Override
    public CompletableFuture<Map<String, Object>> searchTemplate(List<String> indices, Map<String, Object> requestBody,
                                                                 Map<String, String> params) {
        return async(() -> doSearch(indices, renderTemplateBody(requestBody == null ? Map.of() : requestBody), params));
    }

    @Override
    public CompletableFuture<Map<String, Object>> renderTemplate(Map<String, Object> requestBody) {
        return async(() -> Map.of("template_output", renderTemplateBody(requestBody == null ? Map.of() : requestBody)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> rankEval(List<String> indices, Map<String, Object> requestBody) {
        return async(() -> {
            List<String> resolved = engine.resolve(indices, Map.of());
            Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
            Map<String, Object> metric = SettingsMaps.asMap(body.get("metric"));
            String metricName = metric == null || metric.isEmpty() ? "precision" : metric.keySet().iterator().next();
            Map<String, Object> metricOpts = metric == null ? null : SettingsMaps.asMap(metric.get(metricName));
            int k = metricOpts == null ? 10 : SearchEngine.intValue(metricOpts.get("k"), 10);
            Map<String, Object> details = new LinkedHashMap<>();
            double sum = 0;
            int count = 0;
            List<Object> requests = body.get("requests") instanceof List<?> l ? (List<Object>) l : List.of();
            try (MultiShardSearcher searcher = MultiShardSearcher.open(engine.shardsFor(resolved))) {
                for (Object r : requests) {
                    Map<String, Object> req = SettingsMaps.asMap(r);
                    if (req == null) {
                        continue;
                    }
                    String id = String.valueOf(req.get("id"));
                    Map<String, Object> searchBody = SettingsMaps.asMap(req.get("request"));
                    Query query = engine.toQuery(resolved, searchBody == null ? null : SettingsMaps.asMap(searchBody.get("query")));
                    Map<Integer, Integer> ratings = new LinkedHashMap<>();
                    List<Object> ratingList = req.get("ratings") instanceof List<?> rl ? (List<Object>) rl : List.of();
                    for (Object ro : ratingList) {
                        Map<String, Object> rating = SettingsMaps.asMap(ro);
                        if (rating == null) {
                            continue;
                        }
                        String ratedIndex = rating.get("_index") == null ? null : String.valueOf(rating.get("_index"));
                        Integer doc = searcher.findDoc(ratedIndex, String.valueOf(rating.get("_id")));
                        if (doc != null) {
                            ratings.put(doc, SearchEngine.intValue(rating.get("rating"), 0));
                        }
                    }
                    RankEval.Metrics m = RankEval.evaluate(searcher.searcher(), query, ratings, k);
                    double score = switch (metricName) {
                        case "dcg" -> m.dcg();
                        case "ndcg" -> m.ndcg();
                        case "mean_reciprocal_rank" -> m.reciprocalRank();
                        default -> m.precisionAtK();
                    };
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("metric_score", score);
                    detail.put("metric_details", Map.of("precision", m.precisionAtK(), "dcg", m.dcg(), "ndcg", m.ndcg(),
                        "mean_reciprocal_rank", m.reciprocalRank()));
                    details.put(id, detail);
                    sum += score;
                    count++;
                }
            } catch (IOException e) {
                throw wrap(e);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("metric_score", count == 0 ? 0.0 : sum / count);
            out.put("details", details);
            out.put("failures", Map.of());
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> termsEnum(String index, Map<String, Object> requestBody) {
        return async(() -> {
            List<String> resolved = engine.resolve(List.of(index), Map.of());
            Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
            if (body.get("field") == null) {
                throw new RestApiException(400, "[field] is required");
            }
            String field = String.valueOf(body.get("field"));
            String prefix = body.get("string") == null ? "" : String.valueOf(body.get("string"));
            int size = SearchEngine.intValue(body.get("size"), 10);
            TreeMap<String, Integer> merged = new TreeMap<>();
            Map<ShardId, IndexShard> shards = engine.shardsFor(resolved);
            try (MultiShardSearcher searcher = MultiShardSearcher.open(shards)) {
                for (LeafReader leaf : searcher.leafReaders()) {
                    for (TermsEnumLister.TermCount tc : TermsEnumLister.list(leaf, field, prefix, size)) {
                        merged.merge(tc.term(), tc.docFreq(), Integer::sum);
                    }
                }
            } catch (IOException e) {
                throw wrap(e);
            }
            List<String> terms = new ArrayList<>();
            for (String term : merged.keySet()) {
                if (terms.size() >= size) {
                    break;
                }
                terms.add(term);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_shards", Map.of("total", shards.size(), "successful", shards.size(), "failed", 0));
            out.put("terms", terms);
            out.put("complete", true);
            return out;
        });
    }

    @Override
    public void close() {
        for (String id : new ArrayList<>(scrolls.keySet())) {
            releaseScroll(id);
        }
        for (String id : new ArrayList<>(pits.keySet())) {
            closePointInTime(id).join();
        }
        scrollService.close();
        pitService.close();
        asyncSearchService.close();
    }
}
