package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.action.search.LocalSearchExecutor;
import com.naqqa.elasticsearch.action.search.SearchCoordinator;
import com.naqqa.elasticsearch.action.search.SearchRequest;
import com.naqqa.elasticsearch.action.search.SearchResponse;
import com.naqqa.elasticsearch.action.write.SourceUtils;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.mapper.BooleanFieldMapper;
import com.naqqa.elasticsearch.index.mapper.DateFieldMapper;
import com.naqqa.elasticsearch.index.mapper.FieldMapper;
import com.naqqa.elasticsearch.index.mapper.KeywordFieldMapper;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.mapper.NumberFieldMapper;
import com.naqqa.elasticsearch.index.mapper.TextFieldMapper;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.monitor.NodeCounters;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SearchEngine {

    public record ParsedSearch(Query query, int from, int size, Sort sort, List<String> sortFieldNames,
                               Map<String, Object> aggs, boolean fetchSource, List<String> includes, List<String> excludes,
                               boolean version, boolean seqNoPrimaryTerm, String pitId, Float minScore) {
    }

    private final ClusterStateManager clusterStateManager;
    private final IndicesService indicesService;
    private final SearchCoordinator coordinator;
    private final QueryFactory queryFactory;
    private final IndexResolver indexResolver;
    private final NodeCounters counters;

    public SearchEngine(ClusterStateManager clusterStateManager, IndicesService indicesService, SearchCoordinator coordinator,
                        QueryFactory queryFactory, IndexResolver indexResolver, NodeCounters counters) {
        this.clusterStateManager = clusterStateManager;
        this.indicesService = indicesService;
        this.coordinator = coordinator;
        this.queryFactory = queryFactory;
        this.indexResolver = indexResolver;
        this.counters = counters;
    }

    public QueryFactory queryFactory() {
        return queryFactory;
    }

    public String nodeId() {
        return clusterStateManager.localNode() == null ? "_na_" : clusterStateManager.localNode().getId();
    }

    public void setVectorAccessorProvider(com.naqqa.elasticsearch.search.vectors.query.VectorSegmentAccessorProvider provider) {
        queryFactory.setVectorProvider(provider);
    }

    public Map<String, Object> search(List<String> indices, Map<String, Object> body, Map<String, String> params,
                                      Map<String, Object> aliasFilter) throws IOException {
        SearchSpec spec = SearchSpec.parse(body, params);
        boolean indexScopedFilter = aliasFilter != null && containsIndexClause(aliasFilter);
        if (!spec.requiresRichExecution() && !indexScopedFilter) {
            Map<String, Object> effective = body == null ? new LinkedHashMap<>() : body;
            if (aliasFilter != null && !aliasFilter.isEmpty()) {
                effective = new LinkedHashMap<>(effective);
                effective.put("query", SearchExecutor.withFilter(spec.hasQuery ? spec.queryClause : null, aliasFilter));
            }
            ParsedSearch parsed = parse(indices, effective, params);
            SearchResponse response = execute(indices, parsed);
            return render(response, parsed, params);
        }
        long start = counters.query.start();
        boolean ok = false;
        Map<ShardId, EngineSearcher> searchers = new LinkedHashMap<>();
        try {
            for (Map.Entry<ShardId, IndexShard> e : shardsFor(indices).entrySet()) {
                searchers.put(e.getKey(), e.getValue().acquireSearcher());
            }
            Map<String, Object> out = new SearchExecutor(this).execute(indices, searchers, spec, aliasFilter);
            ok = true;
            return out;
        } finally {
            for (EngineSearcher s : searchers.values()) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            counters.query.end(start, ok);
        }
    }

    public Map<String, Object> searchOnSearchers(List<String> indices, Map<ShardId, EngineSearcher> searchers, Map<String, Object> body,
                                                 Map<String, String> params) throws IOException {
        long start = counters.query.start();
        boolean ok = false;
        try {
            SearchSpec spec = SearchSpec.parse(body, params);
            Map<String, Object> out = new SearchExecutor(this).execute(indices, searchers, spec, null);
            ok = true;
            return out;
        } finally {
            counters.query.end(start, ok);
        }
    }

    private static boolean containsIndexClause(Object node) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if ("_index".equals(e.getKey()) || containsIndexClause(e.getValue())) {
                    return true;
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) {
                if (containsIndexClause(o)) {
                    return true;
                }
            }
        }
        return false;
    }

    public IndicesService indicesService() {
        return indicesService;
    }

    public List<String> resolve(List<String> expressions, Map<String, String> params) {
        boolean ignoreUnavailable = params != null && "true".equals(params.get("ignore_unavailable"));
        return indexResolver.resolve(clusterStateManager.state(), expressions, false, ignoreUnavailable);
    }

    public Map<ShardId, IndexShard> shardsFor(List<String> indices) {
        Map<ShardId, IndexShard> out = new LinkedHashMap<>();
        for (String index : indices) {
            IndexService service = indicesService.indexService(index);
            if (service == null) {
                continue;
            }
            for (int i = 0; i < service.metadata().getNumberOfShards(); i++) {
                IndexShard shard = service.shard(i);
                if (shard != null) {
                    out.put(new ShardId(index, i), shard);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public ParsedSearch parse(List<String> indices, Map<String, Object> body, Map<String, String> params) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Map<String, String> p = params == null ? Map.of() : params;
        Map<String, Object> queryClause = SettingsMaps.asMap(b.get("query"));
        if (queryClause == null && p.get("q") != null) {
            Map<String, Object> qs = new LinkedHashMap<>();
            qs.put("query", p.get("q"));
            if (p.get("df") != null) {
                qs.put("default_field", p.get("df"));
            }
            if (p.get("default_operator") != null) {
                qs.put("default_operator", p.get("default_operator"));
            }
            queryClause = Map.of("query_string", qs);
        }
        java.util.function.Function<String, QueryFactory.FieldType> types = fieldTypes(indices);
        Query query = queryFactory.toQuery(queryClause, types);
        Map<String, Object> postFilter = SettingsMaps.asMap(b.get("post_filter"));
        Map<String, Object> aggs = SettingsMaps.asMap(b.get("aggs") != null ? b.get("aggs") : b.get("aggregations"));
        if (postFilter != null && aggs == null) {
            BooleanQuery.Builder combined = BooleanQuery.builder();
            combined.add(query, BooleanQuery.Occur.MUST);
            combined.add(queryFactory.toQuery(postFilter, types), BooleanQuery.Occur.FILTER);
            query = combined.build();
        }
        int from = intValue(p.get("from") != null ? p.get("from") : b.get("from"), 0);
        int size = intValue(p.get("size") != null ? p.get("size") : b.get("size"), 10);
        if (from < 0 || size < 0) {
            throw new RestApiException(400, "[from] and [size] must be non-negative");
        }
        if (from + size > 10_000) {
            throw new RestApiException(400, "Result window is too large, from + size must be less than or equal to: [10000] but was ["
                + (from + size) + "]");
        }
        List<Object> sortSpecs = new ArrayList<>();
        Object sortObj = b.get("sort");
        if (sortObj instanceof List<?> l) {
            sortSpecs.addAll(l);
        } else if (sortObj != null) {
            sortSpecs.add(sortObj);
        }
        if (p.get("sort") != null) {
            for (String s : p.get("sort").split(",")) {
                sortSpecs.add(s.trim());
            }
        }
        List<SortField> sortFields = new ArrayList<>();
        List<String> sortNames = new ArrayList<>();
        for (Object spec : sortSpecs) {
            if (spec instanceof String s) {
                String field = s;
                String order = null;
                int colon = s.lastIndexOf(':');
                if (colon > 0) {
                    field = s.substring(0, colon);
                    order = s.substring(colon + 1);
                }
                sortFields.add(sortField(indices, field, order));
                sortNames.add(field);
            } else if (spec instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String field = String.valueOf(e.getKey());
                    String order = null;
                    if (e.getValue() instanceof Map<?, ?> opts) {
                        order = opts.get("order") == null ? null : String.valueOf(opts.get("order"));
                    } else if (e.getValue() != null) {
                        order = String.valueOf(e.getValue());
                    }
                    sortFields.add(sortField(indices, field, order));
                    sortNames.add(field);
                }
            }
        }
        Sort sort = sortFields.isEmpty() ? null : new Sort(sortFields.toArray(new SortField[0]));
        boolean fetchSource = true;
        List<String> includes = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        Object src = b.get("_source");
        if (src instanceof Boolean bool) {
            fetchSource = bool;
        } else if (src instanceof String s) {
            includes.add(s);
        } else if (src instanceof List<?> l) {
            for (Object o : l) {
                includes.add(String.valueOf(o));
            }
        } else if (src instanceof Map<?, ?> m) {
            includes.addAll(SettingsMaps.asStringList(m.get("includes") != null ? m.get("includes") : m.get("include")));
            excludes.addAll(SettingsMaps.asStringList(m.get("excludes") != null ? m.get("excludes") : m.get("exclude")));
        }
        if (p.get("_source") != null) {
            String v = p.get("_source");
            if ("false".equals(v)) {
                fetchSource = false;
            } else if (!"true".equals(v)) {
                includes.addAll(SettingsMaps.asStringList(v));
            }
        }
        boolean version = Boolean.TRUE.equals(b.get("version")) || "true".equals(p.get("version"));
        boolean seqNo = Boolean.TRUE.equals(b.get("seq_no_primary_term"));
        String pitId = null;
        Map<String, Object> pit = SettingsMaps.asMap(b.get("pit"));
        if (pit != null && pit.get("id") != null) {
            pitId = String.valueOf(pit.get("id"));
        }
        Float minScore = b.get("min_score") instanceof Number n ? n.floatValue() : null;
        return new ParsedSearch(query, from, size, sort, sortNames, aggs, fetchSource, includes, excludes, version, seqNo,
            pitId, minScore);
    }

    public Query toQuery(List<String> indices, Map<String, Object> clause) {
        return queryFactory.toQuery(clause, fieldTypes(indices));
    }

    public java.util.function.Function<String, QueryFactory.FieldType> fieldTypes(List<String> indices) {
        Map<String, java.util.Optional<QueryFactory.FieldType>> cache = new java.util.HashMap<>();
        return field -> cache.computeIfAbsent(field, f -> java.util.Optional.ofNullable(lookupFieldType(indices, f))).orElse(null);
    }

    private QueryFactory.FieldType lookupFieldType(List<String> indices, String field) {
        for (String index : indices) {
            IndexService service = indicesService.indexService(index);
            if (service == null || service.mapperService().documentMapper() == null) {
                continue;
            }
            FieldMapper mapper = service.mapperService().documentMapper().mapping().fieldMapper(field);
            if (mapper == null) {
                continue;
            }
            String type = mapper.typeName();
            String format = null;
            double scaling = 1.0;
            if (type.startsWith("date") || type.equals("scaled_float")) {
                Map<String, Object> def = fieldDefinition(SettingsMaps.asMap(
                    service.mapperService().documentMapper().mapping().toMapping().toJava()), field);
                if (def != null) {
                    format = def.get("format") == null ? null : String.valueOf(def.get("format"));
                    if (def.get("scaling_factor") != null) {
                        scaling = Double.parseDouble(String.valueOf(def.get("scaling_factor")));
                    }
                }
            }
            return new QueryFactory.FieldType(type, format, scaling);
        }
        return null;
    }

    static Map<String, Object> fieldDefinition(Map<String, Object> mapping, String field) {
        Map<String, Object> current = mapping;
        String[] parts = field.split("\\.");
        Map<String, Object> def = null;
        for (int i = 0; i < parts.length && current != null; i++) {
            Map<String, Object> props = SettingsMaps.asMap(current.get("properties"));
            if (props == null) {
                Map<String, Object> multi = SettingsMaps.asMap(current.get("fields"));
                return multi == null ? null : SettingsMaps.asMap(multi.get(parts[i]));
            }
            def = SettingsMaps.asMap(props.get(parts[i]));
            current = def;
        }
        return def;
    }

    private SortField sortField(List<String> indices, String field, String order) {
        boolean desc = order != null && order.equalsIgnoreCase("desc");
        boolean asc = order != null && order.equalsIgnoreCase("asc");
        if ("_score".equals(field)) {
            return new SortField(null, SortField.Type.SCORE, asc);
        }
        if ("_doc".equals(field)) {
            return new SortField(null, SortField.Type.DOC, desc);
        }
        SortField.Type type = null;
        for (String index : indices) {
            IndexService service = indicesService.indexService(index);
            if (service == null) {
                continue;
            }
            SortField.Type t = fieldSortType(service.mapperService(), field);
            if (t != null) {
                type = t;
                break;
            }
        }
        if (type == null) {
            type = SortField.Type.STRING;
        }
        return new SortField(field, type, desc);
    }

    public static SortField.Type fieldSortType(MapperService mapperService, String field) {
        if (mapperService.documentMapper() == null) {
            return null;
        }
        FieldMapper mapper = mapperService.documentMapper().mapping().fieldMapper(field);
        if (mapper == null) {
            return null;
        }
        if (mapper instanceof TextFieldMapper) {
            throw new RestApiException(400, "Text fields are not optimised for operations that require per-document field data "
                + "like aggregations and sorting, so these operations are disabled by default. Please use a keyword field "
                + "instead. Alternatively, set fielddata=true on [" + field + "]");
        }
        if (mapper instanceof NumberFieldMapper n) {
            return switch (n.numberType()) {
                case DOUBLE, FLOAT, HALF_FLOAT, SCALED_FLOAT -> SortField.Type.DOUBLE;
                default -> SortField.Type.LONG;
            };
        }
        if (mapper instanceof DateFieldMapper || mapper instanceof BooleanFieldMapper) {
            return SortField.Type.LONG;
        }
        if (mapper instanceof KeywordFieldMapper) {
            return SortField.Type.STRING;
        }
        return SortField.Type.STRING;
    }

    public SearchResponse execute(List<String> indices, ParsedSearch parsed) throws IOException {
        long start = counters.query.start();
        boolean ok = false;
        try {
            SearchResponse response;
            if (parsed.pitId() != null) {
                throw new IllegalStateException("pit searches are executed through executeOnSearchers");
            }
            if (indices.size() == 1 && QueryFactory.isTransportable(parsed.query())) {
                ClusterState state = clusterStateManager.state();
                SearchRequest request = new SearchRequest(indices.get(0), parsed.query())
                    .from(parsed.from()).size(parsed.size()).sort(parsed.sort()).aggs(parsed.aggs());
                response = coordinator.search(state.getRoutingTable(), request);
            } else {
                SearchRequest request = new SearchRequest(indices.isEmpty() ? "_none" : indices.get(0), parsed.query())
                    .from(parsed.from()).size(parsed.size()).sort(parsed.sort()).aggs(parsed.aggs());
                response = LocalSearchExecutor.search(shardsFor(indices), request);
            }
            ok = true;
            return response;
        } finally {
            counters.query.end(start, ok);
        }
    }

    public SearchResponse searchRequest(com.naqqa.elasticsearch.cluster.routing.RoutingTable routingTable, SearchRequest request)
        throws IOException {
        if (QueryFactory.isTransportable(request.query())) {
            return coordinator.search(routingTable, request);
        }
        return LocalSearchExecutor.search(shardsFor(List.of(request.index())), request);
    }

    public SearchResponse executeOnSearchers(Map<ShardId, EngineSearcher> searchers, ParsedSearch parsed) throws IOException {
        long start = counters.query.start();
        boolean ok = false;
        try {
            SearchRequest request = new SearchRequest("_pit", parsed.query())
                .from(parsed.from()).size(parsed.size()).sort(parsed.sort()).aggs(parsed.aggs());
            SearchResponse response = LocalSearchExecutor.searchOpen(searchers, request);
            ok = true;
            return response;
        } finally {
            counters.query.end(start, ok);
        }
    }

    public Map<String, Object> render(SearchResponse response, ParsedSearch parsed, Map<String, String> params) {
        long fetchStart = counters.fetch.start();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("took", response.tookMillis());
            out.put("timed_out", response.timedOut());
            Map<String, Object> shards = new LinkedHashMap<>();
            shards.put("total", response.shards().total());
            shards.put("successful", response.shards().successful());
            shards.put("skipped", response.shards().skipped());
            shards.put("failed", response.shards().failed());
            if (!response.failures().isEmpty()) {
                List<Object> failures = new ArrayList<>();
                for (SearchResponse.Failure f : response.failures()) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("shard", f.shardId() == null ? -1 : f.shardId().id());
                    fm.put("index", f.shardId() == null ? null : f.shardId().index());
                    fm.put("node", f.nodeId());
                    fm.put("reason", Map.of("type", "exception", "reason", String.valueOf(f.reason())));
                    failures.add(fm);
                }
                shards.put("failures", failures);
            }
            out.put("_shards", shards);
            Map<String, Object> hits = new LinkedHashMap<>();
            boolean totalAsInt = params != null && "true".equals(params.get("rest_total_hits_as_int"));
            if (totalAsInt) {
                hits.put("total", response.totalHits().value());
            } else {
                Map<String, Object> total = new LinkedHashMap<>();
                total.put("value", response.totalHits().value());
                total.put("relation", response.totalHits().relation().name().equals("EQUAL_TO") ? "eq" : "gte");
                hits.put("total", total);
            }
            Float maxScore = null;
            List<Object> hitList = new ArrayList<>();
            for (SearchResponse.Hit hit : response.hits()) {
                if (parsed.minScore() != null && hit.score() < parsed.minScore()) {
                    continue;
                }
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("_index", hit.index());
                h.put("_id", hit.id());
                boolean scored = parsed.sort() == null;
                float score = hit.score();
                h.put("_score", scored && !Float.isNaN(score) ? (Object) score : null);
                if (scored && !Float.isNaN(score) && (maxScore == null || score > maxScore)) {
                    maxScore = score;
                }
                if (parsed.fetchSource() && hit.source() != null) {
                    Map<String, Object> source = hit.sourceAsMap();
                    if (!parsed.includes().isEmpty() || !parsed.excludes().isEmpty()) {
                        source = SourceUtils.filterSource(source, parsed.includes().isEmpty() ? null : parsed.includes(),
                            parsed.excludes().isEmpty() ? null : parsed.excludes());
                    }
                    h.put("_source", source);
                }
                if (parsed.sort() != null && hit.sortValues() != null) {
                    List<Object> sortValues = new ArrayList<>();
                    for (Object v : hit.sortValues()) {
                        sortValues.add(v instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : v);
                    }
                    h.put("sort", sortValues);
                }
                hitList.add(h);
            }
            hits.put("max_score", maxScore);
            hits.put("hits", hitList);
            out.put("hits", hits);
            if (response.aggregations() != null) {
                out.put("aggregations", response.aggregations());
            }
            return out;
        } finally {
            counters.fetch.end(fetchStart, true);
        }
    }

    public static Map<String, Object> sourceOf(byte[] source) {
        if (source == null) {
            return null;
        }
        Object parsed = JsonValue.parse(source).toJava();
        return SettingsMaps.asMap(parsed);
    }

    public static int intValue(Object v, int defaultValue) {
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new RestApiException(400, "Failed to parse int parameter with value [" + v + "]");
        }
    }
}
