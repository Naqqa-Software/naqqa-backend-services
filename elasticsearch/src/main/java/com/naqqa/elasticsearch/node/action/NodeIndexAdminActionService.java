package com.naqqa.elasticsearch.node.action;

import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.indices.alias.AliasAction;
import com.naqqa.elasticsearch.indices.alias.AliasMetadata;
import com.naqqa.elasticsearch.indices.datastream.DataStream;
import com.naqqa.elasticsearch.indices.datastream.DataStreamService;
import com.naqqa.elasticsearch.indices.resize.CloneIndexService;
import com.naqqa.elasticsearch.indices.resize.IndexNotReadOnlyException;
import com.naqqa.elasticsearch.indices.resize.ShrinkIndexService;
import com.naqqa.elasticsearch.indices.resize.SplitIndexService;
import com.naqqa.elasticsearch.indices.rollover.IndexStatsSnapshot;
import com.naqqa.elasticsearch.indices.rollover.RolloverConditions;
import com.naqqa.elasticsearch.indices.rollover.RolloverResult;
import com.naqqa.elasticsearch.indices.rollover.RolloverService;
import com.naqqa.elasticsearch.indices.template.ComponentTemplate;
import com.naqqa.elasticsearch.indices.template.IndexTemplateV2;
import com.naqqa.elasticsearch.indices.template.LegacyTemplate;
import com.naqqa.elasticsearch.indices.template.ResolvedTemplate;
import com.naqqa.elasticsearch.indices.template.Template;
import com.naqqa.elasticsearch.indices.template.TemplateResolver;
import com.naqqa.elasticsearch.indices.template.TemplateService;
import com.naqqa.elasticsearch.node.indices.IndexService;
import com.naqqa.elasticsearch.node.indices.IndicesService;
import com.naqqa.elasticsearch.node.indices.MetadataIndexService;
import com.naqqa.elasticsearch.node.monitor.NodeCounters;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.indices.IndexAdminActionService;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;
import com.naqqa.elasticsearch.rest.support.ResourceAlreadyExistsException;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class NodeIndexAdminActionService implements IndexAdminActionService {

    private static final Pattern ROLLOVER_SUFFIX = Pattern.compile("^(.*?)-(\\d+)$");

    private final MetadataIndexService metadataService;
    private final IndicesService indicesService;
    private final TemplateService templateService;
    private final TemplateResolver templateResolver;
    private final DataStreamService dataStreamService;
    private final Set<String> dataStreamNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> indexTemplateSources = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> componentTemplateSources = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> legacyTemplateSources = new ConcurrentHashMap<>();
    private final Set<String> frozen = ConcurrentHashMap.newKeySet();
    private final IndexResolver indexResolver;
    private final RolloverService rolloverService = new RolloverService();
    private final NodeCounters counters;
    private final ExecutorService executor;
    private final Path indicesPath;
    private final long waitForActiveShardsMillis;

    public NodeIndexAdminActionService(MetadataIndexService metadataService, IndicesService indicesService,
                                       TemplateService templateService, TemplateResolver templateResolver,
                                       DataStreamService dataStreamService, NodeCounters counters, ExecutorService executor,
                                       Path indicesPath, long waitForActiveShardsMillis) {
        this.metadataService = metadataService;
        this.indicesService = indicesService;
        this.templateService = templateService;
        this.templateResolver = templateResolver;
        this.dataStreamService = dataStreamService;
        this.counters = counters;
        this.executor = executor;
        this.indicesPath = indicesPath;
        this.waitForActiveShardsMillis = waitForActiveShardsMillis;
        this.indexResolver = new IndexResolver(this::dataStreamBackingIndices, this::dataStreamIndicesMatching);
    }

    public Map<String, Object> aliasFilter(List<String> expressions) {
        if (expressions == null || expressions.size() != 1) {
            return null;
        }
        String expr = expressions.get(0);
        if (expr.contains(",") || Regex.isSimpleMatchPattern(expr) || state().getMetadata().index(expr) != null) {
            return null;
        }
        Set<String> indices = metadataService.aliasService().resolveIndices(expr);
        Map<String, Object> filter = null;
        for (String index : indices) {
            AliasMetadata alias = metadataService.aliasService().getAliases(index).get(expr);
            if (alias == null || !alias.hasFilter()) {
                return null;
            }
            if (filter == null) {
                filter = alias.getFilter();
            } else if (!filter.equals(alias.getFilter())) {
                return null;
            }
        }
        return filter;
    }

    public IndexResolver indexResolver() {
        return indexResolver;
    }

    public TemplateService templateService() {
        return templateService;
    }

    public Set<String> dataStreamNames() {
        return dataStreamNames;
    }

    public DataStreamService dataStreamService() {
        return dataStreamService;
    }

    public Map<String, Map<String, Object>> indexTemplateSources() {
        return indexTemplateSources;
    }

    public Map<String, Map<String, Object>> componentTemplateSources() {
        return componentTemplateSources;
    }

    public Map<String, Map<String, Object>> legacyTemplateSources() {
        return legacyTemplateSources;
    }

    public List<String> dataStreamBackingIndices(String name) {
        if (!dataStreamNames.contains(name)) {
            return List.of();
        }
        DataStream ds = dataStreamService.get(name);
        return ds == null ? List.of() : ds.getBackingIndices();
    }

    private Set<String> dataStreamIndicesMatching(String pattern) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : dataStreamNames) {
            if (Regex.simpleMatch(pattern, name)) {
                out.addAll(dataStreamBackingIndices(name));
            }
        }
        return out;
    }

    private <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private ClusterState state() {
        return metadataService.state();
    }

    private List<String> resolve(List<String> patterns, boolean includeClosed) {
        return indexResolver.resolve(state(), patterns, includeClosed, false);
    }

    private List<String> resolveLenient(List<String> patterns, boolean includeClosed) {
        return indexResolver.resolve(state(), patterns, includeClosed, true);
    }

    private static com.naqqa.elasticsearch.cluster.state.Settings toClusterSettings(Map<String, String> flat) {
        return com.naqqa.elasticsearch.cluster.state.Settings.builder().putAll(flat).build();
    }

    static Map<String, AliasMetadata> parseAliases(Map<String, Object> aliases) {
        Map<String, AliasMetadata> out = new LinkedHashMap<>();
        if (aliases == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : aliases.entrySet()) {
            Map<String, Object> spec = SettingsMaps.asMap(e.getValue());
            AliasMetadata.Builder b = AliasMetadata.builder(e.getKey());
            if (spec != null) {
                Map<String, Object> filter = SettingsMaps.asMap(spec.get("filter"));
                if (filter != null) {
                    b.filter(filter);
                }
                if (spec.get("routing") != null) {
                    b.routing(String.valueOf(spec.get("routing")));
                }
                if (spec.get("index_routing") != null) {
                    b.indexRouting(String.valueOf(spec.get("index_routing")));
                }
                if (spec.get("search_routing") != null) {
                    b.searchRouting(String.valueOf(spec.get("search_routing")));
                }
                if (spec.get("is_write_index") != null) {
                    b.writeIndex(Boolean.valueOf(String.valueOf(spec.get("is_write_index"))));
                }
            }
            out.put(e.getKey(), b.build());
        }
        return out;
    }

    private static Map<String, Object> aliasToMap(AliasMetadata a) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (a.hasFilter()) {
            m.put("filter", a.getFilter());
        }
        if (a.getIndexRouting() != null) {
            m.put("index_routing", a.getIndexRouting());
        }
        if (a.getSearchRouting() != null) {
            m.put("search_routing", a.getSearchRouting());
        }
        if (a.isWriteIndexExplicit()) {
            m.put("is_write_index", a.isWriteIndex());
        }
        return m;
    }

    public IndexMetadata createIndexInternal(String index, Map<String, Object> settings, Map<String, Object> mappings,
                                             Map<String, Object> aliases, String forcedUuid, boolean waitForShards) {
        MetadataIndexService.validateIndexName(index);
        Map<String, String> requestSettings = SettingsMaps.flattenIndexSettings(settings == null ? Map.of() : settings);
        ResolvedTemplate resolved = templateResolver.resolve(index, toClusterSettings(requestSettings),
            mappings == null ? Map.of() : mappings, parseAliases(aliases));
        String uuid = forcedUuid != null ? forcedUuid : MetadataIndexService.newUuid();
        Map<String, String> finalSettings = metadataService.defaultedSettings(new LinkedHashMap<>(resolved.getSettings().getAsMap()),
            index, uuid);
        MetadataIndexService.CreateSpec spec = new MetadataIndexService.CreateSpec(index, uuid, finalSettings,
            resolved.getMappings() == null ? Map.of() : resolved.getMappings(), resolved.getAliases());
        return metadataService.createIndex(spec, waitForShards ? waitForActiveShardsMillis : 0L);
    }

    public void ensureWriteTarget(String name) {
        ClusterState state = state();
        if (state.getMetadata().index(name) != null || !state.getMetadata().resolveIndicesForAlias(name).isEmpty()) {
            return;
        }
        if (dataStreamNames.contains(name)) {
            return;
        }
        IndexTemplateV2 template = templateService.findMatchingIndexTemplate(name);
        if (template != null && template.isDataStreamTemplate()) {
            createDataStreamInternal(name);
            return;
        }
        try {
            createIndexInternal(name, Map.of(), Map.of(), Map.of(), null, true);
        } catch (ResourceAlreadyExistsException ignored) {
        }
    }

    @Override
    public CompletableFuture<CreateIndexResult> createIndex(CreateIndexRequest request) {
        return async(() -> {
            if (dataStreamNames.contains(request.index())) {
                throw new ResourceAlreadyExistsException("data stream [" + request.index() + "] already exists");
            }
            IndexMetadata imd = createIndexInternal(request.index(), request.settings(), request.mappings(), request.aliases(),
                null, true);
            return new CreateIndexResult(true, imd != null && metadataService.primariesActive(request.index()), request.index());
        });
    }

    @Override
    public CompletableFuture<AckResult> deleteIndex(List<String> indices) {
        return async(() -> {
            List<String> resolved = new ArrayList<>();
            for (String pattern : indices) {
                for (String p : pattern.split(",")) {
                    if (!Regex.isSimpleMatchPattern(p) && !"_all".equals(p) && state().getMetadata().index(p) == null) {
                        if (!state().getMetadata().resolveIndicesForAlias(p).isEmpty()) {
                            throw new RestApiException(400, "The provided expression [" + p
                                + "] matches an alias, specify the corresponding concrete indices instead.");
                        }
                        throw new IndexNotFoundException(p);
                    }
                }
            }
            resolved.addAll(indexResolver.resolve(state(), indices, true, true));
            for (String name : dataStreamNames) {
                DataStream ds = dataStreamService.get(name);
                if (ds != null && resolved.contains(ds.getWriteIndex())) {
                    throw new RestApiException(400, "index [" + ds.getWriteIndex() + "] is the write index for data stream ["
                        + name + "] and cannot be deleted");
                }
            }
            metadataService.deleteIndices(resolved);
            for (String index : resolved) {
                frozen.remove(index);
            }
            return new AckResult(true);
        });
    }

    private Map<String, Object> settingsView(IndexMetadata imd, boolean flat) {
        Map<String, String> settings = new TreeMap<>(imd.getSettings().getAsMap());
        if (flat) {
            return new LinkedHashMap<>(settings);
        }
        return SettingsMaps.unflatten(settings);
    }

    private Map<String, Object> mappingView(String index) {
        IndexService service = indicesService.indexService(index);
        if (service != null && service.mapperService().documentMapper() != null) {
            Map<String, Object> m = SettingsMaps.asMap(service.mapperService().documentMapper().mapping().toMapping().toJava());
            if (m != null) {
                return m;
            }
        }
        IndexMetadata imd = state().getMetadata().index(index);
        return imd == null ? Map.of() : new LinkedHashMap<>(imd.getMappings());
    }

    private Map<String, Object> aliasesView(String index, List<String> aliasPatterns) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (AliasMetadata a : metadataService.aliasService().getAliases(index).values()) {
            if (aliasPatterns == null || aliasPatterns.isEmpty() || matchesAny(aliasPatterns, a.getAlias())) {
                out.put(a.getAlias(), aliasToMap(a));
            }
        }
        return out;
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        for (String pattern : patterns) {
            for (String p : pattern.split(",")) {
                if ("_all".equals(p) || "*".equals(p) || (Regex.isSimpleMatchPattern(p) ? Regex.simpleMatch(p, value) : p.equals(value))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getIndex(List<String> indices, Map<String, String> params) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String index : resolve(indices, true)) {
                IndexMetadata imd = state().getMetadata().index(index);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("aliases", aliasesView(index, null));
                entry.put("mappings", mappingView(index));
                entry.put("settings", settingsView(imd, params != null && "true".equals(params.get("flat_settings"))));
                for (String ds : dataStreamNames) {
                    if (dataStreamBackingIndices(ds).contains(index)) {
                        entry.put("data_stream", ds);
                    }
                }
                out.put(index, entry);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Boolean> indexExists(List<String> indices) {
        return async(() -> {
            try {
                for (String expr : indices) {
                    if (resolve(List.of(expr), true).isEmpty()) {
                        return false;
                    }
                }
                return true;
            } catch (IndexNotFoundException e) {
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<AckResult> openIndex(List<String> indices) {
        return async(() -> {
            metadataService.setState(resolve(indices, true), IndexMetadata.State.OPEN);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> closeIndex(List<String> indices) {
        return async(() -> {
            metadataService.setState(resolve(indices, true), IndexMetadata.State.CLOSE);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> putMapping(List<String> indices, Map<String, Object> mapping) {
        return async(() -> {
            metadataService.putMapping(resolve(indices, true), mapping);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> getMapping(List<String> indices) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String index : resolve(indices, true)) {
                out.put(index, Map.of("mappings", mappingView(index)));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<AckResult> putSettings(List<String> indices, Map<String, Object> settings) {
        return async(() -> {
            Map<String, Object> body = settings;
            if (body.get("settings") instanceof Map<?, ?> && body.size() == 1) {
                body = SettingsMaps.asMap(body.get("settings"));
            }
            metadataService.putSettings(resolve(indices, true), SettingsMaps.flattenIndexSettings(body));
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> getSettings(List<String> indices, Map<String, String> params) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean flat = params != null && "true".equals(params.get("flat_settings"));
            for (String index : resolve(indices, true)) {
                out.put(index, Map.of("settings", settingsView(state().getMetadata().index(index), flat)));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<AckResult> putAlias(List<String> indices, String alias, Map<String, Object> aliasBody) {
        return async(() -> {
            List<AliasAction> actions = new ArrayList<>();
            for (String index : resolve(indices, true)) {
                actions.add(aliasAction(index, alias, aliasBody));
            }
            metadataService.applyAliasActions(actions);
            return new AckResult(true);
        });
    }

    private static AliasAction aliasAction(String index, String alias, Map<String, Object> spec) {
        AliasAction.Builder b = AliasAction.add().index(index).alias(alias);
        if (spec != null) {
            Map<String, Object> filter = SettingsMaps.asMap(spec.get("filter"));
            if (filter != null) {
                b.filter(filter);
            }
            if (spec.get("routing") != null) {
                b.routing(String.valueOf(spec.get("routing")));
            }
            if (spec.get("index_routing") != null) {
                b.indexRouting(String.valueOf(spec.get("index_routing")));
            }
            if (spec.get("search_routing") != null) {
                b.searchRouting(String.valueOf(spec.get("search_routing")));
            }
            if (spec.get("is_write_index") != null) {
                b.writeIndex(Boolean.valueOf(String.valueOf(spec.get("is_write_index"))));
            }
        }
        return b.build();
    }

    @Override
    public CompletableFuture<AckResult> deleteAlias(List<String> indices, List<String> aliases) {
        return async(() -> {
            List<AliasAction> actions = new ArrayList<>();
            for (String index : resolve(indices, true)) {
                for (AliasMetadata a : metadataService.aliasService().getAliases(index).values()) {
                    if (matchesAny(aliases, a.getAlias())) {
                        actions.add(AliasAction.remove().index(index).alias(a.getAlias()).build());
                    }
                }
            }
            if (actions.isEmpty()) {
                throw new RestApiException(404, "aliases " + aliases + " missing");
            }
            metadataService.applyAliasActions(actions);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> updateAliases(Map<String, Object> requestBody) {
        return async(() -> {
            if (!(requestBody.get("actions") instanceof List<?> rawActions)) {
                throw new RestApiException(400, "[actions] is required");
            }
            List<AliasAction> actions = new ArrayList<>();
            for (Object raw : rawActions) {
                Map<String, Object> action = SettingsMaps.asMap(raw);
                if (action == null) {
                    continue;
                }
                for (Map.Entry<String, Object> e : action.entrySet()) {
                    Map<String, Object> spec = SettingsMaps.asMap(e.getValue());
                    if (spec == null) {
                        throw new RestApiException(400, "invalid alias action [" + e.getKey() + "]");
                    }
                    List<String> indexExprs = new ArrayList<>(SettingsMaps.asStringList(spec.get("indices")));
                    if (spec.get("index") != null) {
                        indexExprs.add(String.valueOf(spec.get("index")));
                    }
                    List<String> aliasNames = new ArrayList<>(SettingsMaps.asStringList(spec.get("aliases")));
                    if (spec.get("alias") != null) {
                        aliasNames.add(String.valueOf(spec.get("alias")));
                    }
                    boolean mustExist = Boolean.TRUE.equals(spec.get("must_exist"));
                    for (String index : resolve(indexExprs, true)) {
                        switch (e.getKey()) {
                            case "add" -> {
                                for (String alias : aliasNames) {
                                    actions.add(aliasAction(index, alias, spec));
                                }
                            }
                            case "remove" -> {
                                for (String alias : aliasNames) {
                                    for (AliasMetadata existing : metadataService.aliasService().getAliases(index).values()) {
                                        if (matchesAny(List.of(alias), existing.getAlias())) {
                                            actions.add(AliasAction.remove().index(index).alias(existing.getAlias())
                                                .mustExist(mustExist).build());
                                        }
                                    }
                                }
                            }
                            case "remove_index" -> actions.add(AliasAction.removeIndex(index));
                            default -> throw new RestApiException(400, "Unknown alias action [" + e.getKey() + "]");
                        }
                    }
                }
            }
            metadataService.applyAliasActions(actions);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAlias(List<String> indices, List<String> aliasNames) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean anyMatch = false;
            for (String index : resolveLenient(indices, true)) {
                Map<String, Object> aliases = aliasesView(index, aliasNames);
                if (!aliases.isEmpty()) {
                    anyMatch = true;
                }
                if (!aliases.isEmpty() || aliasNames == null || aliasNames.isEmpty()) {
                    out.put(index, Map.of("aliases", aliases));
                }
            }
            if (!anyMatch && aliasNames != null && !aliasNames.isEmpty()) {
                boolean wildcardOnly = aliasNames.stream().allMatch(Regex::isSimpleMatchPattern);
                if (!wildcardOnly) {
                    throw new RestApiException(404, "alias [" + String.join(",", aliasNames) + "] missing");
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Boolean> aliasExists(List<String> indices, List<String> aliasNames) {
        return async(() -> {
            for (String index : resolveLenient(indices, true)) {
                if (!aliasesView(index, aliasNames).isEmpty()) {
                    return true;
                }
            }
            return false;
        });
    }

    private Map<String, Object> shardsHeader(int total, int successful) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("successful", successful);
        m.put("failed", 0);
        return m;
    }

    private int totalCopies(List<String> indices) {
        int total = 0;
        for (String index : indices) {
            IndexMetadata imd = state().getMetadata().index(index);
            if (imd != null) {
                total += imd.getNumberOfShards() * (1 + imd.getNumberOfReplicas());
            }
        }
        return total;
    }

    @Override
    public CompletableFuture<Map<String, Object>> refresh(List<String> indices) {
        return async(() -> {
            List<String> resolved = resolve(indices, false);
            int ok = 0;
            for (String index : resolved) {
                for (IndexShard shard : indicesService.localShards(index)) {
                    long start = counters.refresh.start();
                    boolean success = false;
                    try {
                        shard.refresh();
                        success = true;
                        ok++;
                    } catch (IOException e) {
                        throw new RestApiException(500, e.getMessage(), e);
                    } finally {
                        counters.refresh.end(start, success);
                    }
                }
            }
            return Map.of("_shards", shardsHeader(totalCopies(resolved), ok));
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> flush(List<String> indices, Map<String, String> params) {
        return async(() -> {
            List<String> resolved = resolve(indices, false);
            boolean force = params != null && "true".equals(params.get("force"));
            int ok = 0;
            for (String index : resolved) {
                for (IndexShard shard : indicesService.localShards(index)) {
                    long start = counters.flush.start();
                    boolean success = false;
                    try {
                        shard.flush(force || true);
                        success = true;
                        ok++;
                    } catch (IOException e) {
                        throw new RestApiException(500, e.getMessage(), e);
                    } finally {
                        counters.flush.end(start, success);
                    }
                }
            }
            return Map.of("_shards", shardsHeader(totalCopies(resolved), ok));
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> forceMerge(List<String> indices, Map<String, String> params) {
        return async(() -> {
            List<String> resolved = resolve(indices, false);
            int maxSegments = params != null && params.get("max_num_segments") != null
                ? Integer.parseInt(params.get("max_num_segments")) : 1;
            int ok = 0;
            for (String index : resolved) {
                for (IndexShard shard : indicesService.localShards(index)) {
                    long start = counters.merge.start();
                    boolean success = false;
                    try {
                        counters.mergedDocs.add(shard.docCount());
                        shard.forceMerge(maxSegments);
                        success = true;
                        ok++;
                    } catch (IOException e) {
                        throw new RestApiException(500, e.getMessage(), e);
                    } finally {
                        counters.merge.end(start, success);
                    }
                }
            }
            return Map.of("_shards", shardsHeader(totalCopies(resolved), ok));
        });
    }

    @Override
    public CompletableFuture<AckResult> clearCache(List<String> indices, Map<String, String> params) {
        return async(() -> {
            resolve(indices, false);
            return new AckResult(true);
        });
    }

    public static long directorySize(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    public Map<String, Object> shardStats(IndexService service, int shardId, IndexShard shard) {
        EngineStats stats = shard.stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", Map.of("count", stats.numDocs(), "deleted", stats.numDeletedDocs()));
        m.put("store", Map.of("size_in_bytes", directorySize(service.shardPath(shardId).resolve("index"))));
        m.put("segments", Map.of("count", stats.segmentCount()));
        m.put("translog", Map.of("operations", stats.translogNumOps(), "size_in_bytes", stats.translogSizeInBytes()));
        m.put("seq_no", Map.of("max_seq_no", stats.maxSeqNo(), "local_checkpoint", stats.localCheckpoint()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void accumulate(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object existing = target.get(e.getKey());
            if (e.getValue() instanceof Map<?, ?> sub) {
                Map<String, Object> t = existing instanceof Map<?, ?> tm ? (Map<String, Object>) tm : new LinkedHashMap<>();
                accumulate(t, (Map<String, Object>) sub);
                target.put(e.getKey(), t);
            } else if (e.getValue() instanceof Number n) {
                long prev = existing instanceof Number pn ? pn.longValue() : 0L;
                if ("max_seq_no".equals(e.getKey()) || "local_checkpoint".equals(e.getKey())) {
                    target.put(e.getKey(), Math.max(prev, n.longValue()));
                } else {
                    target.put(e.getKey(), prev + n.longValue());
                }
            }
        }
    }

    @Override
    public CompletableFuture<Map<String, Object>> stats(List<String> indices, Map<String, String> params) {
        return async(() -> {
            List<String> resolved = resolve(indices, false);
            Map<String, Object> all = new LinkedHashMap<>();
            Map<String, Object> allPrimaries = new LinkedHashMap<>();
            Map<String, Object> perIndex = new LinkedHashMap<>();
            int shardCount = 0;
            for (String index : resolved) {
                IndexService service = indicesService.indexService(index);
                Map<String, Object> primaries = new LinkedHashMap<>();
                if (service != null) {
                    for (Map.Entry<Integer, IndexShard> e : new TreeMap<>(service.shards()).entrySet()) {
                        accumulate(primaries, shardStats(service, e.getKey(), e.getValue()));
                        shardCount++;
                    }
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("uuid", state().getMetadata().index(index).getIndexUUID());
                entry.put("primaries", primaries);
                entry.put("total", primaries);
                perIndex.put(index, entry);
                accumulate(allPrimaries, primaries);
            }
            all.put("primaries", allPrimaries);
            all.put("total", allPrimaries);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_shards", shardsHeader(totalCopies(resolved), shardCount));
            out.put("_all", all);
            out.put("indices", perIndex);
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> segments(List<String> indices) {
        return async(() -> {
            List<String> resolved = resolve(indices, false);
            Map<String, Object> perIndex = new LinkedHashMap<>();
            int shardCount = 0;
            for (String index : resolved) {
                IndexService service = indicesService.indexService(index);
                Map<String, Object> shards = new LinkedHashMap<>();
                if (service != null) {
                    for (Map.Entry<Integer, IndexShard> e : new TreeMap<>(service.shards()).entrySet()) {
                        Map<String, Object> segments = new LinkedHashMap<>();
                        try (EngineSearcher searcher = e.getValue().acquireSearcher()) {
                            for (SegmentReader sr : searcher.leaves()) {
                                Map<String, Object> seg = new LinkedHashMap<>();
                                seg.put("num_docs", sr.numDocs());
                                seg.put("deleted_docs", sr.maxDoc() - sr.numDocs());
                                seg.put("committed", !sr.isDirty());
                                seg.put("search", true);
                                segments.put(sr.name(), seg);
                            }
                        } catch (IOException ex) {
                            throw new RestApiException(500, ex.getMessage(), ex);
                        }
                        shards.put(Integer.toString(e.getKey()), List.of(Map.of(
                            "routing", Map.of("state", "STARTED", "primary", true, "node", nodeIdFor(index, e.getKey())),
                            "num_committed_segments", segments.size(), "num_search_segments", segments.size(),
                            "segments", segments)));
                        shardCount++;
                    }
                }
                perIndex.put(index, Map.of("shards", shards));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_shards", shardsHeader(totalCopies(resolved), shardCount));
            out.put("indices", perIndex);
            return out;
        });
    }

    private String nodeIdFor(String index, int shard) {
        IndexRoutingTable irt = state().getRoutingTable().index(index);
        if (irt == null || irt.shard(shard) == null || irt.shard(shard).primaryShard() == null) {
            return null;
        }
        return irt.shard(shard).primaryShard().currentNodeId();
    }

    @Override
    public CompletableFuture<Map<String, Object>> recovery(List<String> indices, Map<String, String> params) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean activeOnly = params != null && "true".equals(params.get("active_only"));
            for (String index : resolve(indices, false)) {
                List<Object> shards = new ArrayList<>();
                IndexRoutingTable irt = state().getRoutingTable().index(index);
                if (irt != null && !activeOnly) {
                    for (IndexShardRoutingTable table : new TreeMap<>(irt.getShards()).values()) {
                        for (ShardRouting sr : table.getShards()) {
                            if (sr.currentNodeId() == null) {
                                continue;
                            }
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("id", sr.getShardId());
                            s.put("type", sr.primary() ? "EMPTY_STORE" : "PEER");
                            s.put("stage", sr.active() ? "DONE" : "INDEX");
                            s.put("primary", sr.primary());
                            s.put("target", Map.of("id", sr.currentNodeId()));
                            shards.add(s);
                        }
                    }
                }
                out.put(index, Map.of("shards", shards));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> shardStores(List<String> indices, Map<String, String> params) {
        return async(() -> {
            Map<String, Object> perIndex = new LinkedHashMap<>();
            for (String index : resolve(indices, false)) {
                Map<String, Object> shards = new LinkedHashMap<>();
                IndexRoutingTable irt = state().getRoutingTable().index(index);
                if (irt != null) {
                    for (IndexShardRoutingTable table : new TreeMap<>(irt.getShards()).values()) {
                        List<Object> stores = new ArrayList<>();
                        for (ShardRouting sr : table.getShards()) {
                            if (sr.currentNodeId() == null || sr.allocationId() == null) {
                                continue;
                            }
                            Map<String, Object> store = new LinkedHashMap<>();
                            store.put(sr.currentNodeId(), Map.of("name", sr.currentNodeId()));
                            store.put("allocation_id", sr.allocationId().getId());
                            store.put("allocation", sr.primary() ? "primary" : "replica");
                            stores.add(store);
                        }
                        shards.put(Integer.toString(table.getShardId().id()), Map.of("stores", stores));
                    }
                }
                perIndex.put(index, Map.of("shards", shards));
            }
            return Map.of("indices", perIndex);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> diskUsage(List<String> indices, Map<String, String> params) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String index : resolve(indices, false)) {
                IndexService service = indicesService.indexService(index);
                long size = service == null ? 0L : directorySize(service.indexPath());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("store_size", ByteSizeValue.ofBytes(size).toString());
                entry.put("store_size_in_bytes", size);
                out.put(index, entry);
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> resolveIndex(List<String> names) {
        return async(() -> {
            ClusterState state = state();
            List<Object> indicesOut = new ArrayList<>();
            Map<String, Set<String>> aliasToIndices = new TreeMap<>();
            List<Object> dataStreamsOut = new ArrayList<>();
            List<String> patterns = names == null || names.isEmpty() ? List.of("*") : names;
            for (IndexMetadata imd : state.getMetadata().getIndices().values()) {
                boolean matched = matchesAny(patterns, imd.getIndex());
                for (String alias : imd.getAliases().keySet()) {
                    if (matchesAny(patterns, alias)) {
                        aliasToIndices.computeIfAbsent(alias, k -> new LinkedHashSet<>()).add(imd.getIndex());
                    }
                }
                if (matched) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", imd.getIndex());
                    if (!imd.getAliases().isEmpty()) {
                        m.put("aliases", new ArrayList<>(imd.getAliases().keySet()));
                    }
                    m.put("attributes", List.of(imd.getState() == IndexMetadata.State.OPEN ? "open" : "closed"));
                    for (String ds : dataStreamNames) {
                        if (dataStreamBackingIndices(ds).contains(imd.getIndex())) {
                            m.put("data_stream", ds);
                        }
                    }
                    indicesOut.add(m);
                }
            }
            List<Object> aliasesOut = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : aliasToIndices.entrySet()) {
                aliasesOut.add(Map.of("name", e.getKey(), "indices", new ArrayList<>(e.getValue())));
            }
            for (String ds : new TreeMap<>(dataStreamNames.stream().collect(java.util.stream.Collectors.toMap(s -> s, s -> s))).keySet()) {
                if (matchesAny(patterns, ds)) {
                    DataStream stream = dataStreamService.get(ds);
                    dataStreamsOut.add(Map.of("name", ds, "backing_indices", stream.getBackingIndices(),
                        "timestamp_field", stream.getTimestampField()));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("indices", indicesOut);
            out.put("aliases", aliasesOut);
            out.put("data_streams", dataStreamsOut);
            return out;
        });
    }

    private IndexStatsSnapshot statsSnapshot(String index) {
        IndexService service = indicesService.indexService(index);
        long docs = 0;
        long size = 0;
        long maxPrimary = 0;
        long age = 0;
        if (service != null) {
            age = System.currentTimeMillis() - service.creationTimeMillis();
            for (Map.Entry<Integer, IndexShard> e : service.shards().entrySet()) {
                docs += e.getValue().docCount();
                long shardSize = directorySize(service.shardPath(e.getKey()).resolve("index"));
                size += shardSize;
                maxPrimary = Math.max(maxPrimary, shardSize);
            }
        }
        return new IndexStatsSnapshot(age, docs, size, maxPrimary);
    }

    public static RolloverConditions parseConditions(Map<String, Object> conditions) {
        RolloverConditions.Builder b = RolloverConditions.builder();
        if (conditions == null) {
            return b.build();
        }
        if (conditions.get("max_age") != null) {
            b.maxAge(TimeValue.parseTimeValue(String.valueOf(conditions.get("max_age")), "max_age"));
        }
        if (conditions.get("max_docs") != null) {
            b.maxDocs(Long.parseLong(String.valueOf(conditions.get("max_docs"))));
        }
        if (conditions.get("max_size") != null) {
            b.maxSize(ByteSizeValue.parseBytesSizeValue(String.valueOf(conditions.get("max_size")), "max_size"));
        }
        if (conditions.get("max_primary_shard_size") != null) {
            b.maxPrimaryShardSize(ByteSizeValue.parseBytesSizeValue(String.valueOf(conditions.get("max_primary_shard_size")),
                "max_primary_shard_size"));
        }
        return b.build();
    }

    static String nextRolloverName(String oldIndex) {
        Matcher m = ROLLOVER_SUFFIX.matcher(oldIndex);
        if (!m.matches()) {
            throw new RestApiException(400, "index name [" + oldIndex + "] does not match pattern '^.*-\\d+$'");
        }
        int width = m.group(2).length();
        long next = Long.parseLong(m.group(2)) + 1;
        return m.group(1) + "-" + String.format("%0" + Math.max(6, width) + "d", next);
    }

    public Map<String, Object> rolloverInternal(String target, String newIndex, Map<String, Object> requestBody, boolean dryRun) {
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        Map<String, Object> conditionsBody = SettingsMaps.asMap(body.get("conditions"));
        RolloverConditions conditions = parseConditions(conditionsBody);
        boolean isDataStream = dataStreamNames.contains(target);
        String oldIndex;
        if (isDataStream) {
            oldIndex = dataStreamService.get(target).getWriteIndex();
        } else {
            oldIndex = metadataService.aliasService().resolveWriteIndex(target);
            if (oldIndex == null) {
                if (metadataService.aliasService().resolveIndices(target).isEmpty()) {
                    throw new IndexNotFoundException(target);
                }
                throw new RestApiException(400, "rollover target [" + target + "] does not point to a write index");
            }
        }
        RolloverResult result = conditions.isEmpty() ? new RolloverResult(true, List.of())
            : rolloverService.evaluateConditions(statsSnapshot(oldIndex), conditions);
        String resolvedNew = isDataStream ? DataStream.backingIndexName(target, dataStreamService.get(target).getGeneration() + 1)
            : (newIndex != null ? newIndex : nextRolloverName(oldIndex));
        Map<String, Object> conditionResults = new LinkedHashMap<>();
        if (conditionsBody != null) {
            for (Map.Entry<String, Object> e : conditionsBody.entrySet()) {
                String key = "[" + e.getKey() + ": " + e.getValue() + "]";
                boolean met = false;
                for (String matched : result.matchedConditions()) {
                    if (matched.contains(e.getKey())) {
                        met = true;
                    }
                }
                conditionResults.put(key, met);
            }
        }
        boolean rolledOver = false;
        if (!dryRun && result.shouldRollover()) {
            if (isDataStream) {
                IndexMetadata oldMeta = state().getMetadata().index(oldIndex);
                createIndexInternal(resolvedNew, Map.of(), Map.of(), Map.of(), null, true);
                dataStreamService.rollover(target);
            } else {
                if (state().getMetadata().index(resolvedNew) != null) {
                    throw new ResourceAlreadyExistsException("index [" + resolvedNew + "] already exists");
                }
                Map<String, Object> settings = SettingsMaps.asMap(body.get("settings"));
                Map<String, Object> mappings = SettingsMaps.asMap(body.get("mappings"));
                Map<String, Object> aliases = SettingsMaps.asMap(body.get("aliases"));
                createIndexInternal(resolvedNew, settings, mappings, aliases, null, true);
                AliasMetadata oldAlias = metadataService.aliasService().getAliases(oldIndex).get(target);
                List<AliasAction> actions = new ArrayList<>();
                if (oldAlias != null && oldAlias.isWriteIndexExplicit()) {
                    actions.add(AliasAction.add().index(oldIndex).alias(target).writeIndex(false)
                        .indexRouting(oldAlias.getIndexRouting()).searchRouting(oldAlias.getSearchRouting())
                        .filter(oldAlias.hasFilter() ? oldAlias.getFilter() : null).build());
                    actions.add(AliasAction.add().index(resolvedNew).alias(target).writeIndex(true).build());
                } else {
                    actions.add(AliasAction.remove().index(oldIndex).alias(target).build());
                    actions.add(AliasAction.add().index(resolvedNew).alias(target).build());
                }
                metadataService.applyAliasActions(actions);
            }
            rolledOver = true;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("acknowledged", rolledOver);
        out.put("shards_acknowledged", rolledOver);
        out.put("old_index", oldIndex);
        out.put("new_index", resolvedNew);
        out.put("rolled_over", rolledOver);
        out.put("dry_run", dryRun);
        out.put("conditions", conditionResults);
        return out;
    }

    @Override
    public CompletableFuture<Map<String, Object>> rollover(String alias, String newIndex, Map<String, Object> requestBody, boolean dryRun) {
        return async(() -> rolloverInternal(alias, newIndex, requestBody, dryRun));
    }

    private enum ResizeType { SHRINK, SPLIT, CLONE }

    public Map<String, Object> resizeInternal(ResizeType type, String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        MetadataIndexService.validateIndexName(targetIndex);
        IndexMetadata source = state().getMetadata().index(sourceIndex);
        if (source == null) {
            throw new IndexNotFoundException(sourceIndex);
        }
        if (state().getMetadata().index(targetIndex) != null) {
            throw new ResourceAlreadyExistsException("index [" + targetIndex + "] already exists");
        }
        IndexService sourceService = indicesService.indexService(sourceIndex);
        if (sourceService == null) {
            throw new RestApiException(400, "source index [" + sourceIndex + "] is not open on this node");
        }
        boolean readOnly = "true".equalsIgnoreCase(source.getSettings().get("index.blocks.write"))
            || "true".equalsIgnoreCase(source.getSettings().get("index.blocks.read_only"));
        Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
        Map<String, String> overrides = SettingsMaps.flattenIndexSettings(
            SettingsMaps.asMap(body.get("settings")) == null ? Map.of() : SettingsMaps.asMap(body.get("settings")));
        int sourceShards = source.getNumberOfShards();
        int targetShards = overrides.get("index.number_of_shards") != null ? Integer.parseInt(overrides.get("index.number_of_shards"))
            : switch (type) {
                case SHRINK -> 1;
                case SPLIT -> throw new RestApiException(400, "index.number_of_shards is required for split");
                case CLONE -> sourceShards;
            };
        Map<String, String> settings = new LinkedHashMap<>(source.getSettings().getAsMap());
        settings.remove("index.uuid");
        settings.remove("index.creation_date");
        settings.remove("index.provided_name");
        settings.remove("index.blocks.write");
        settings.remove("index.blocks.read_only");
        settings.putAll(overrides);
        settings.put("index.number_of_shards", Integer.toString(targetShards));
        settings.put("index.resize.source.name", sourceIndex);
        settings.put("index.resize.source.uuid", source.getIndexUUID());
        String uuid = MetadataIndexService.newUuid();
        Path targetPath = indicesPath.resolve(uuid);
        MapperService targetMapper = sourceService.mapperService();
        List<IndexShard> built;
        try {
            List<IndexShard> sourceList = sourceService.shardsInOrder();
            built = switch (type) {
                case SHRINK -> ShrinkIndexService.shrink(targetPath, sourceShards, targetShards, sourceList, targetMapper, readOnly);
                case SPLIT -> SplitIndexService.split(targetPath, sourceShards, targetShards, sourceList, targetMapper, readOnly);
                case CLONE -> {
                    if (targetShards != sourceShards) {
                        throw new RestApiException(400, "clone requires the same number of shards [" + sourceShards + "]");
                    }
                    yield CloneIndexService.clone(targetPath, sourceList, targetMapper, readOnly);
                }
            };
        } catch (IndexNotReadOnlyException e) {
            throw new RestApiException(400, "index " + sourceIndex + " must be read-only to resize index. use \"index.blocks.write=true\"", e);
        } catch (IllegalArgumentException e) {
            throw new RestApiException(400, e.getMessage(), e);
        } catch (IOException e) {
            throw new RestApiException(500, e.getMessage(), e);
        }
        for (IndexShard shard : built) {
            try {
                shard.flushAndClose();
            } catch (IOException ignored) {
            }
        }
        Map<String, Object> settingsNested = SettingsMaps.unflatten(settings);
        Map<String, Object> mappings = new LinkedHashMap<>(source.getMappings());
        Map<String, Object> aliases = SettingsMaps.asMap(body.get("aliases"));
        createIndexInternal(targetIndex, settingsNested, mappings, aliases, uuid, true);
        return Map.of("acknowledged", true, "shards_acknowledged", metadataService.primariesActive(targetIndex), "index", targetIndex);
    }

    @Override
    public CompletableFuture<Map<String, Object>> shrink(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        return async(() -> resizeInternal(ResizeType.SHRINK, sourceIndex, targetIndex, requestBody));
    }

    @Override
    public CompletableFuture<Map<String, Object>> split(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        return async(() -> resizeInternal(ResizeType.SPLIT, sourceIndex, targetIndex, requestBody));
    }

    @Override
    public CompletableFuture<Map<String, Object>> clone(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        return async(() -> resizeInternal(ResizeType.CLONE, sourceIndex, targetIndex, requestBody));
    }

    public String shrinkForIlm(String index, int shards) {
        String target = "shrink-" + index;
        metadataService.putSettings(List.of(index), Map.of("index.blocks.write", "true"));
        resizeInternal(ResizeType.SHRINK, index, target, Map.of("settings", Map.of("index.number_of_shards", shards)));
        return target;
    }

    @Override
    public CompletableFuture<AckResult> freeze(String index) {
        return async(() -> {
            List<String> resolved = resolve(List.of(index), true);
            metadataService.putSettings(resolved, Map.of("index.frozen", "true", "index.blocks.write", "true"));
            frozen.addAll(resolved);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> unfreeze(String index) {
        return async(() -> {
            List<String> resolved = resolve(List.of(index), true);
            metadataService.putSettings(resolved, Map.of("index.frozen", "false", "index.blocks.write", "false"));
            frozen.removeAll(resolved);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> addBlock(List<String> indices, String block) {
        return async(() -> {
            String key = switch (block) {
                case "write" -> "index.blocks.write";
                case "read_only" -> "index.blocks.read_only";
                case "read_only_allow_delete" -> "index.blocks.read_only_allow_delete";
                case "metadata" -> "index.blocks.metadata";
                case "read" -> "index.blocks.read";
                default -> throw new RestApiException(400, "unknown block [" + block + "]");
            };
            metadataService.putSettings(resolve(indices, true), Map.of(key, "true"));
            return new AckResult(true);
        });
    }

    static Template parseTemplateBody(Map<String, Object> templateSection) {
        if (templateSection == null) {
            return Template.EMPTY;
        }
        Map<String, Object> settings = SettingsMaps.asMap(templateSection.get("settings"));
        Map<String, Object> mappings = SettingsMaps.asMap(templateSection.get("mappings"));
        Map<String, Object> aliases = SettingsMaps.asMap(templateSection.get("aliases"));
        return Template.builder()
            .settings(toClusterSettings(SettingsMaps.flattenIndexSettings(settings == null ? Map.of() : settings)))
            .mappings(mappings)
            .aliases(parseAliases(aliases))
            .build();
    }

    @Override
    public CompletableFuture<AckResult> putIndexTemplate(String name, Map<String, Object> template) {
        return async(() -> {
            List<String> patterns = SettingsMaps.asStringList(template.get("index_patterns"));
            if (patterns.isEmpty()) {
                throw new RestApiException(400, "index template [" + name + "] must define [index_patterns]");
            }
            List<String> composedOf = SettingsMaps.asStringList(template.get("composed_of"));
            for (String component : composedOf) {
                if (templateService.getComponentTemplate(component) == null) {
                    throw new RestApiException(400, "index template [" + name + "] specifies component templates ["
                        + component + "] that do not exist");
                }
            }
            long priority = template.get("priority") == null ? 0L : Long.parseLong(String.valueOf(template.get("priority")));
            Map<String, Object> ds = SettingsMaps.asMap(template.get("data_stream"));
            String timestampField = null;
            if (ds != null && ds.get("timestamp_field") instanceof Map<?, ?> tf && tf.get("name") != null) {
                timestampField = String.valueOf(tf.get("name"));
            }
            IndexTemplateV2 parsed = new IndexTemplateV2(name, patterns, composedOf, priority,
                parseTemplateBody(SettingsMaps.asMap(template.get("template"))), ds != null, timestampField,
                SettingsMaps.asMap(template.get("_meta")));
            try {
                templateService.putIndexTemplate(parsed, false);
            } catch (RuntimeException e) {
                throw new RestApiException(400, e.getMessage(), e);
            }
            indexTemplateSources.put(name, new LinkedHashMap<>(template));
            return new AckResult(true);
        });
    }

    private Map<String, Object> collect(Map<String, Map<String, Object>> store, List<String> names, String listKey, String bodyKey,
                                        String missingLabel) {
        List<Object> list = new ArrayList<>();
        boolean any = false;
        for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(store).entrySet()) {
            if (names == null || names.isEmpty() || matchesAny(names, e.getKey())) {
                list.add(Map.of("name", e.getKey(), bodyKey, e.getValue()));
                any = true;
            }
        }
        if (!any && names != null && !names.isEmpty() && names.stream().noneMatch(Regex::isSimpleMatchPattern)) {
            throw new RestApiException(404, missingLabel + " " + names + " not found");
        }
        return Map.of(listKey, list);
    }

    @Override
    public CompletableFuture<Map<String, Object>> getIndexTemplate(List<String> names) {
        return async(() -> collect(indexTemplateSources, names, "index_templates", "index_template", "index template matching"));
    }

    @Override
    public CompletableFuture<AckResult> deleteIndexTemplate(String name) {
        return async(() -> {
            if (indexTemplateSources.remove(name) == null) {
                throw new RestApiException(404, "index_template [" + name + "] missing");
            }
            templateService.deleteIndexTemplate(name);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> putComponentTemplate(String name, Map<String, Object> template) {
        return async(() -> {
            Long version = template.get("version") == null ? null : Long.parseLong(String.valueOf(template.get("version")));
            ComponentTemplate parsed = new ComponentTemplate(name, parseTemplateBody(SettingsMaps.asMap(template.get("template"))),
                version, SettingsMaps.asMap(template.get("_meta")));
            templateService.putComponentTemplate(parsed, false);
            componentTemplateSources.put(name, new LinkedHashMap<>(template));
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> getComponentTemplate(List<String> names) {
        return async(() -> collect(componentTemplateSources, names, "component_templates", "component_template",
            "component template matching"));
    }

    @Override
    public CompletableFuture<AckResult> deleteComponentTemplate(String name) {
        return async(() -> {
            if (!componentTemplateSources.containsKey(name)) {
                throw new RestApiException(404, "component_template [" + name + "] missing");
            }
            try {
                templateService.deleteComponentTemplate(name);
            } catch (RuntimeException e) {
                throw new RestApiException(400, e.getMessage(), e);
            }
            componentTemplateSources.remove(name);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> putLegacyTemplate(String name, Map<String, Object> template) {
        return async(() -> {
            List<String> patterns = SettingsMaps.asStringList(template.get("index_patterns") != null
                ? template.get("index_patterns") : template.get("template"));
            int order = template.get("order") == null ? 0 : Integer.parseInt(String.valueOf(template.get("order")));
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("settings", template.get("settings"));
            section.put("mappings", template.get("mappings"));
            section.put("aliases", template.get("aliases"));
            templateService.putLegacyTemplate(new LegacyTemplate(name, patterns, order, parseTemplateBody(section)));
            legacyTemplateSources.put(name, new LinkedHashMap<>(template));
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> getLegacyTemplate(List<String> names) {
        return async(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(legacyTemplateSources).entrySet()) {
                if (names == null || names.isEmpty() || matchesAny(names, e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
            if (out.isEmpty() && names != null && !names.isEmpty() && names.stream().noneMatch(Regex::isSimpleMatchPattern)) {
                throw new RestApiException(404, "index_template " + names + " missing");
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<AckResult> deleteLegacyTemplate(String name) {
        return async(() -> {
            if (legacyTemplateSources.remove(name) == null) {
                throw new RestApiException(404, "index_template [" + name + "] missing");
            }
            templateService.deleteLegacyTemplate(name);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> simulateIndex(String index, Map<String, Object> requestBody) {
        return async(() -> {
            ResolvedTemplate resolved = templateResolver.simulateTemplateResolution(index, null);
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("settings", SettingsMaps.unflatten(resolved.getSettings().getAsMap()));
            template.put("mappings", resolved.getMappings());
            Map<String, Object> aliases = new LinkedHashMap<>();
            for (AliasMetadata a : resolved.getAliases().values()) {
                aliases.put(a.getAlias(), aliasToMap(a));
            }
            template.put("aliases", aliases);
            List<Object> overlapping = new ArrayList<>();
            IndexTemplateV2 winner = templateService.findMatchingIndexTemplate(index);
            for (String name : indexTemplateSources.keySet()) {
                IndexTemplateV2 t = templateService.getIndexTemplate(name);
                if (t != null && t.matches(index) && (winner == null || !winner.getName().equals(name))) {
                    overlapping.add(Map.of("name", name, "index_patterns", t.getIndexPatterns()));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("template", template);
            out.put("overlapping", overlapping);
            return out;
        });
    }

    private void createDataStreamInternal(String name) {
        if (state().getMetadata().index(name) != null) {
            throw new RestApiException(400, "data stream [" + name + "] conflicts with index");
        }
        DataStream ds;
        try {
            ds = dataStreamService.createDataStream(name);
        } catch (com.naqqa.elasticsearch.common.exception.ResourceAlreadyExistsException e) {
            throw new ResourceAlreadyExistsException(e.getMessage());
        } catch (com.naqqa.elasticsearch.common.exception.ElasticsearchException e) {
            throw new RestApiException(400, e.getMessage(), e);
        }
        dataStreamNames.add(name);
        try {
            createIndexInternal(ds.getWriteIndex(), Map.of("index", Map.of("hidden", "true")), Map.of(), Map.of(), null, true);
        } catch (RuntimeException e) {
            dataStreamNames.remove(name);
            dataStreamService.delete(name);
            throw e;
        }
    }

    @Override
    public CompletableFuture<AckResult> createDataStream(String name) {
        return async(() -> {
            createDataStreamInternal(name);
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<AckResult> deleteDataStream(String name) {
        return async(() -> {
            List<String> matched = new ArrayList<>();
            for (String ds : dataStreamNames) {
                if (matchesAny(List.of(name), ds)) {
                    matched.add(ds);
                }
            }
            if (matched.isEmpty()) {
                throw new RestApiException(404, "data_stream [" + name + "] missing");
            }
            for (String ds : matched) {
                List<String> backing = new ArrayList<>(dataStreamBackingIndices(ds));
                dataStreamNames.remove(ds);
                dataStreamService.delete(ds);
                List<String> existing = new ArrayList<>();
                for (String b : backing) {
                    if (state().getMetadata().index(b) != null) {
                        existing.add(b);
                    }
                }
                metadataService.deleteIndices(existing);
            }
            return new AckResult(true);
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> getDataStreams(List<String> names) {
        return async(() -> {
            List<Object> out = new ArrayList<>();
            boolean explicit = names != null && !names.isEmpty();
            for (String ds : new TreeMap<>(dataStreamNames.stream().collect(java.util.stream.Collectors.toMap(s -> s, s -> s))).keySet()) {
                if (explicit && !matchesAny(names, ds)) {
                    continue;
                }
                DataStream stream = dataStreamService.get(ds);
                if (stream == null) {
                    continue;
                }
                List<Object> backing = new ArrayList<>();
                for (String index : stream.getBackingIndices()) {
                    IndexMetadata imd = state().getMetadata().index(index);
                    backing.add(Map.of("index_name", index, "index_uuid", imd == null ? "" : imd.getIndexUUID()));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", ds);
                m.put("timestamp_field", Map.of("name", stream.getTimestampField()));
                m.put("indices", backing);
                m.put("generation", stream.getGeneration());
                m.put("status", metadataService.primariesActive(stream.getWriteIndex()) ? "GREEN" : "RED");
                IndexTemplateV2 t = templateService.findMatchingIndexTemplate(ds);
                if (t != null) {
                    m.put("template", t.getName());
                }
                out.add(m);
            }
            if (out.isEmpty() && explicit && names.stream().noneMatch(Regex::isSimpleMatchPattern)) {
                throw new RestApiException(404, "data_stream " + names + " missing");
            }
            return Map.of("data_streams", out);
        });
    }
}
