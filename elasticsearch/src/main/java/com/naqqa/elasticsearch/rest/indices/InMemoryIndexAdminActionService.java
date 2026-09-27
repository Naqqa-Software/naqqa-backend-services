package com.naqqa.elasticsearch.rest.indices;

import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.rest.support.ResourceAlreadyExistsException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemoryIndexAdminActionService implements IndexAdminActionService {

    public static final class IndexMeta {
        public Map<String, Object> settings = new LinkedHashMap<>();
        public Map<String, Object> mappings = new LinkedHashMap<>();
        public Map<String, Map<String, Object>> aliases = new LinkedHashMap<>();
        public boolean open = true;
        public boolean frozen = false;
        public Set<String> blocks = ConcurrentHashMap.newKeySet();
    }

    private final Map<String, IndexMeta> indices = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> indexTemplates = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> componentTemplates = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> legacyTemplates = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> dataStreams = new ConcurrentHashMap<>();
    private final AtomicInteger rolloverCounter = new AtomicInteger(1);

    public Map<String, IndexMeta> rawIndices() {
        return indices;
    }

    private List<String> resolveIndices(List<String> patterns) {
        List<String> effective = (patterns == null || patterns.isEmpty()) ? List.of("*") : patterns;
        Set<String> result = new LinkedHashSet<>();
        for (String pattern : effective) {
            String effectivePattern = "_all".equals(pattern) ? "*" : pattern;
            if (Regex.isSimpleMatchPattern(effectivePattern)) {
                for (String name : indices.keySet()) {
                    if (Regex.simpleMatch(effectivePattern, name)) {
                        result.add(name);
                    }
                }
            } else {
                if (!indices.containsKey(effectivePattern)) {
                    throw new IndexNotFoundException(effectivePattern);
                }
                result.add(effectivePattern);
            }
        }
        return new ArrayList<>(result);
    }

    private IndexMeta requireIndex(String name) {
        IndexMeta meta = indices.get(name);
        if (meta == null) {
            throw new IndexNotFoundException(name);
        }
        return meta;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<CreateIndexResult> createIndex(CreateIndexRequest request) {
        if (indices.containsKey(request.index())) {
            return CompletableFuture.failedFuture(new ResourceAlreadyExistsException("index [" + request.index() + "] already exists"));
        }
        IndexMeta meta = new IndexMeta();
        meta.settings.put("number_of_shards", "1");
        meta.settings.put("number_of_replicas", "1");
        if (request.settings() != null) {
            meta.settings.putAll(request.settings());
        }
        if (request.mappings() != null) {
            meta.mappings.putAll(request.mappings());
        }
        if (request.aliases() != null) {
            for (Map.Entry<String, Object> entry : request.aliases().entrySet()) {
                meta.aliases.put(entry.getKey(), entry.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
            }
        }
        indices.put(request.index(), meta);
        return CompletableFuture.completedFuture(new CreateIndexResult(true, true, request.index()));
    }

    @Override
    public CompletableFuture<AckResult> deleteIndex(List<String> patterns) {
        List<String> resolved = resolveIndices(patterns);
        resolved.forEach(indices::remove);
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getIndex(List<String> patterns, Map<String, String> params) {
        List<String> resolved = resolveIndices(patterns);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : resolved) {
            IndexMeta meta = indices.get(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("aliases", aliasesView(meta));
            entry.put("mappings", meta.mappings);
            entry.put("settings", Map.of("index", meta.settings));
            result.put(name, entry);
        }
        return CompletableFuture.completedFuture(result);
    }

    private Map<String, Object> aliasesView(IndexMeta meta) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : meta.aliases.entrySet()) {
            result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    @Override
    public CompletableFuture<Boolean> indexExists(List<String> patterns) {
        for (String pattern : patterns) {
            String effective = "_all".equals(pattern) ? "*" : pattern;
            if (Regex.isSimpleMatchPattern(effective)) {
                boolean any = indices.keySet().stream().anyMatch(name -> Regex.simpleMatch(effective, name));
                if (!any) {
                    return CompletableFuture.completedFuture(false);
                }
            } else if (!indices.containsKey(effective)) {
                return CompletableFuture.completedFuture(false);
            }
        }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<AckResult> openIndex(List<String> patterns) {
        for (String name : resolveIndices(patterns)) {
            requireIndex(name).open = true;
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> closeIndex(List<String> patterns) {
        for (String name : resolveIndices(patterns)) {
            requireIndex(name).open = false;
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<AckResult> putMapping(List<String> patterns, Map<String, Object> mapping) {
        for (String name : resolveIndices(patterns)) {
            IndexMeta meta = requireIndex(name);
            if (mapping.get("properties") instanceof Map<?, ?> newProps) {
                Map<String, Object> existingProps = meta.mappings.get("properties") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : new LinkedHashMap<>();
                existingProps.putAll((Map<String, Object>) newProps);
                meta.mappings.put("properties", existingProps);
            }
            for (Map.Entry<String, Object> entry : mapping.entrySet()) {
                if (!entry.getKey().equals("properties")) {
                    meta.mappings.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getMapping(List<String> patterns) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            result.put(name, Map.of("mappings", indices.get(name).mappings));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<AckResult> putSettings(List<String> patterns, Map<String, Object> settings) {
        for (String name : resolveIndices(patterns)) {
            requireIndex(name).settings.putAll(flatten(settings));
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> settings) {
        if (settings.get("index") instanceof Map<?, ?> nested) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) nested).entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Object> e : settings.entrySet()) {
                if (!e.getKey().equals("index")) {
                    result.put(e.getKey(), e.getValue());
                }
            }
            return result;
        }
        return settings;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getSettings(List<String> patterns, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            result.put(name, Map.of("settings", Map.of("index", indices.get(name).settings)));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<AckResult> putAlias(List<String> patterns, String alias, Map<String, Object> aliasBody) {
        for (String name : resolveIndices(patterns)) {
            requireIndex(name).aliases.put(alias, aliasBody == null ? new LinkedHashMap<>() : new LinkedHashMap<>(aliasBody));
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> deleteAlias(List<String> patterns, List<String> aliasPatterns) {
        boolean removedAny = false;
        for (String name : resolveIndices(patterns)) {
            IndexMeta meta = requireIndex(name);
            for (String aliasPattern : aliasPatterns) {
                List<String> matching = new ArrayList<>();
                for (String alias : meta.aliases.keySet()) {
                    if (Regex.isSimpleMatchPattern(aliasPattern) ? Regex.simpleMatch(aliasPattern, alias) : alias.equals(aliasPattern)) {
                        matching.add(alias);
                    }
                }
                for (String alias : matching) {
                    meta.aliases.remove(alias);
                    removedAny = true;
                }
            }
        }
        if (!removedAny) {
            throw new RestApiException(404, "aliases " + aliasPatterns + " missing");
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<AckResult> updateAliases(Map<String, Object> requestBody) {
        Object rawActions = requestBody.get("actions");
        if (!(rawActions instanceof List<?> actions)) {
            throw new IllegalArgumentException("actions is required");
        }
        for (Object rawAction : actions) {
            Map<String, Object> action = (Map<String, Object>) rawAction;
            for (Map.Entry<String, Object> entry : action.entrySet()) {
                Map<String, Object> spec = (Map<String, Object>) entry.getValue();
                String indexPattern = spec.get("index") != null ? String.valueOf(spec.get("index")) : null;
                String alias = spec.get("alias") != null ? String.valueOf(spec.get("alias")) : null;
                switch (entry.getKey()) {
                    case "add" -> await(putAlias(List.of(indexPattern), alias, spec));
                    case "remove" -> await(deleteAlias(List.of(indexPattern), List.of(alias)));
                    case "remove_index" -> await(deleteIndex(List.of(indexPattern)));
                    default -> throw new IllegalArgumentException("unsupported alias action [" + entry.getKey() + "]");
                }
            }
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAlias(List<String> patterns, List<String> aliasNames) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            IndexMeta meta = indices.get(name);
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : meta.aliases.entrySet()) {
                if (aliasNames.isEmpty() || matchesAny(aliasNames, e.getKey())) {
                    filtered.put(e.getKey(), e.getValue());
                }
            }
            result.put(name, Map.of("aliases", filtered));
        }
        return CompletableFuture.completedFuture(result);
    }

    private static boolean matchesAny(List<String> patterns, String value) {
        for (String pattern : patterns) {
            if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, value) : pattern.equals(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<Boolean> aliasExists(List<String> patterns, List<String> aliasNames) {
        for (String name : resolveIndices(patterns)) {
            IndexMeta meta = indices.get(name);
            for (String alias : meta.aliases.keySet()) {
                if (aliasNames.isEmpty() || matchesAny(aliasNames, alias)) {
                    return CompletableFuture.completedFuture(true);
                }
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<Map<String, Object>> refresh(List<String> patterns) {
        resolveIndices(patterns);
        return CompletableFuture.completedFuture(Map.of("_shards", Map.of("total", 1, "successful", 1, "failed", 0)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> flush(List<String> patterns, Map<String, String> params) {
        resolveIndices(patterns);
        return CompletableFuture.completedFuture(Map.of("_shards", Map.of("total", 1, "successful", 1, "failed", 0)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> forceMerge(List<String> patterns, Map<String, String> params) {
        resolveIndices(patterns);
        return CompletableFuture.completedFuture(Map.of("_shards", Map.of("total", 1, "successful", 1, "failed", 0)));
    }

    @Override
    public CompletableFuture<AckResult> clearCache(List<String> patterns, Map<String, String> params) {
        resolveIndices(patterns);
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> stats(List<String> patterns, Map<String, String> params) {
        Map<String, Object> perIndex = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            perIndex.put(name, Map.of("primaries", Map.of("docs", Map.of("count", 0, "deleted", 0)),
                "total", Map.of("docs", Map.of("count", 0, "deleted", 0))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_shards", Map.of("total", perIndex.size(), "successful", perIndex.size(), "failed", 0));
        result.put("_all", Map.of("primaries", Map.of("docs", Map.of("count", 0, "deleted", 0)),
            "total", Map.of("docs", Map.of("count", 0, "deleted", 0))));
        result.put("indices", perIndex);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> segments(List<String> patterns) {
        Map<String, Object> perIndex = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            perIndex.put(name, Map.of("shards", Map.of()));
        }
        return CompletableFuture.completedFuture(Map.of("indices", perIndex));
    }

    @Override
    public CompletableFuture<Map<String, Object>> recovery(List<String> patterns, Map<String, String> params) {
        Map<String, Object> perIndex = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            perIndex.put(name, Map.of("shards", List.of()));
        }
        return CompletableFuture.completedFuture(perIndex);
    }

    @Override
    public CompletableFuture<Map<String, Object>> shardStores(List<String> patterns, Map<String, String> params) {
        Map<String, Object> perIndex = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            perIndex.put(name, Map.of("shards", Map.of()));
        }
        return CompletableFuture.completedFuture(Map.of("indices", perIndex));
    }

    @Override
    public CompletableFuture<Map<String, Object>> diskUsage(List<String> patterns, Map<String, String> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : resolveIndices(patterns)) {
            result.put(name, Map.of("store_size", "0b", "store_size_in_bytes", 0));
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> resolveIndex(List<String> patterns) {
        List<Object> matchedIndices = new ArrayList<>();
        Set<String> aliasNames = new LinkedHashSet<>();
        for (String pattern : patterns) {
            for (Map.Entry<String, IndexMeta> entry : indices.entrySet()) {
                if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, entry.getKey()) : pattern.equals(entry.getKey())) {
                    matchedIndices.add(Map.of("name", entry.getKey(), "aliases", List.copyOf(entry.getValue().aliases.keySet()),
                        "attributes", List.of("open")));
                    aliasNames.addAll(entry.getValue().aliases.keySet());
                }
            }
        }
        List<Object> aliasesList = new ArrayList<>();
        for (String alias : aliasNames) {
            aliasesList.add(Map.of("name", alias, "indices", indicesForAlias(alias)));
        }
        return CompletableFuture.completedFuture(Map.of("indices", matchedIndices, "aliases", aliasesList, "data_streams", List.of()));
    }

    private List<String> indicesForAlias(String alias) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, IndexMeta> entry : indices.entrySet()) {
            if (entry.getValue().aliases.containsKey(alias)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Map<String, Object>> rollover(String alias, String newIndex, Map<String, Object> requestBody, boolean dryRun) {
        List<String> currentTargets = indicesForAlias(alias);
        if (currentTargets.isEmpty()) {
            throw new IndexNotFoundException(alias);
        }
        String oldIndex = currentTargets.get(0);
        String targetIndex = newIndex != null ? newIndex : alias + "-" + String.format("%06d", rolloverCounter.getAndIncrement());
        Map<String, Object> conditionsResult = new LinkedHashMap<>();
        if (requestBody != null && requestBody.get("conditions") instanceof Map<?, ?> conditions) {
            for (Object key : conditions.keySet()) {
                conditionsResult.put(String.valueOf(key), true);
            }
        }
        boolean rolledOver = !dryRun;
        if (rolledOver) {
            IndexMeta source = requireIndex(oldIndex);
            IndexMeta target = new IndexMeta();
            target.settings.putAll(source.settings);
            target.mappings.putAll(source.mappings);
            indices.put(targetIndex, target);
            source.aliases.remove(alias);
            target.aliases.put(alias, Map.of("is_write_index", true));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acknowledged", true);
        result.put("shards_acknowledged", true);
        result.put("old_index", oldIndex);
        result.put("new_index", targetIndex);
        result.put("rolled_over", rolledOver);
        result.put("dry_run", dryRun);
        result.put("conditions", conditionsResult);
        return CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<Map<String, Object>> resizeIndex(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        IndexMeta source = requireIndex(sourceIndex);
        if (indices.containsKey(targetIndex)) {
            return CompletableFuture.failedFuture(new ResourceAlreadyExistsException("index [" + targetIndex + "] already exists"));
        }
        IndexMeta target = new IndexMeta();
        target.settings.putAll(source.settings);
        target.mappings.putAll(source.mappings);
        if (requestBody != null && requestBody.get("settings") instanceof Map<?, ?> settingsOverride) {
            target.settings.putAll(flatten((Map<String, Object>) settingsOverride));
        }
        indices.put(targetIndex, target);
        return CompletableFuture.completedFuture(Map.of("acknowledged", true, "shards_acknowledged", true, "index", targetIndex));
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> shrink(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        return resizeIndex(sourceIndex, targetIndex, requestBody);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> split(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        return resizeIndex(sourceIndex, targetIndex, requestBody);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> clone(String sourceIndex, String targetIndex, Map<String, Object> requestBody) {
        return resizeIndex(sourceIndex, targetIndex, requestBody);
    }

    @Override
    public CompletableFuture<AckResult> freeze(String index) {
        requireIndex(index).frozen = true;
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> unfreeze(String index) {
        requireIndex(index).frozen = false;
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> addBlock(List<String> patterns, String block) {
        for (String name : resolveIndices(patterns)) {
            requireIndex(name).blocks.add(block);
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> putIndexTemplate(String name, Map<String, Object> template) {
        indexTemplates.put(name, new LinkedHashMap<>(template));
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getIndexTemplate(List<String> names) {
        return CompletableFuture.completedFuture(collectTemplates(indexTemplates, names, "index_templates", "name", "index_template"));
    }

    @Override
    public CompletableFuture<AckResult> deleteIndexTemplate(String name) {
        if (indexTemplates.remove(name) == null) {
            throw new RestApiException(404, "index_template [" + name + "] missing");
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> putComponentTemplate(String name, Map<String, Object> template) {
        componentTemplates.put(name, new LinkedHashMap<>(template));
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getComponentTemplate(List<String> names) {
        return CompletableFuture.completedFuture(collectTemplates(componentTemplates, names, "component_templates", "name", "component_template"));
    }

    @Override
    public CompletableFuture<AckResult> deleteComponentTemplate(String name) {
        if (componentTemplates.remove(name) == null) {
            throw new RestApiException(404, "component_template [" + name + "] missing");
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> putLegacyTemplate(String name, Map<String, Object> template) {
        legacyTemplates.put(name, new LinkedHashMap<>(template));
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getLegacyTemplate(List<String> names) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> effective = (names == null || names.isEmpty()) ? List.copyOf(legacyTemplates.keySet()) : names;
        for (String pattern : effective) {
            for (Map.Entry<String, Map<String, Object>> entry : legacyTemplates.entrySet()) {
                if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, entry.getKey()) : entry.getKey().equals(pattern)) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (result.isEmpty() && names != null && !names.isEmpty()) {
            throw new RestApiException(404, "legacy template " + names + " missing");
        }
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<AckResult> deleteLegacyTemplate(String name) {
        if (legacyTemplates.remove(name) == null) {
            throw new RestApiException(404, "template [" + name + "] missing");
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    private Map<String, Object> collectTemplates(Map<String, Map<String, Object>> store, List<String> names, String listKey,
                                                   String nameKey, String bodyKey) {
        List<Object> result = new ArrayList<>();
        List<String> effective = (names == null || names.isEmpty()) ? List.copyOf(store.keySet()) : names;
        boolean anyFound = false;
        for (String pattern : effective) {
            for (Map.Entry<String, Map<String, Object>> entry : store.entrySet()) {
                if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, entry.getKey()) : entry.getKey().equals(pattern)) {
                    result.add(Map.of(nameKey, entry.getKey(), bodyKey, entry.getValue()));
                    anyFound = true;
                }
            }
        }
        if (!anyFound && names != null && !names.isEmpty()) {
            throw new RestApiException(404, "template " + names + " missing");
        }
        return Map.of(listKey, result);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> simulateIndex(String index, Map<String, Object> requestBody) {
        Map<String, Object> mergedSettings = new LinkedHashMap<>();
        Map<String, Object> mergedMappings = new LinkedHashMap<>();
        Map<String, Object> mergedAliases = new LinkedHashMap<>();
        List<String> overlapping = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : indexTemplates.entrySet()) {
            Object rawPatterns = entry.getValue().get("index_patterns");
            List<String> patterns = rawPatterns instanceof List<?> l ? (List<String>) l : List.of();
            for (String pattern : patterns) {
                if (Regex.simpleMatch(pattern, index)) {
                    overlapping.add(entry.getKey());
                    Object template = entry.getValue().get("template");
                    if (template instanceof Map<?, ?> t) {
                        if (t.get("settings") instanceof Map<?, ?> s) {
                            mergedSettings.putAll((Map<String, Object>) s);
                        }
                        if (t.get("mappings") instanceof Map<?, ?> m) {
                            mergedMappings.putAll((Map<String, Object>) m);
                        }
                        if (t.get("aliases") instanceof Map<?, ?> a) {
                            mergedAliases.putAll((Map<String, Object>) a);
                        }
                    }
                    break;
                }
            }
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("settings", mergedSettings);
        template.put("mappings", mergedMappings);
        template.put("aliases", mergedAliases);
        return CompletableFuture.completedFuture(Map.of("template", template, "overlapping", overlapping));
    }

    @Override
    public CompletableFuture<AckResult> createDataStream(String name) {
        if (dataStreams.containsKey(name)) {
            return CompletableFuture.failedFuture(new ResourceAlreadyExistsException("data_stream [" + name + "] already exists"));
        }
        String backingIndex = ".ds-" + name + "-000001";
        IndexMeta meta = new IndexMeta();
        indices.put(backingIndex, meta);
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("name", name);
        stream.put("timestamp_field", Map.of("name", "@timestamp"));
        stream.put("generation", 1);
        stream.put("indices", List.of(Map.of("index_name", backingIndex)));
        dataStreams.put(name, stream);
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<AckResult> deleteDataStream(String name) {
        Map<String, Object> stream = dataStreams.remove(name);
        if (stream == null) {
            throw new RestApiException(404, "data_stream [" + name + "] missing");
        }
        return CompletableFuture.completedFuture(new AckResult(true));
    }

    @Override
    public CompletableFuture<Map<String, Object>> getDataStreams(List<String> names) {
        List<Object> result = new ArrayList<>();
        List<String> effective = (names == null || names.isEmpty()) ? List.copyOf(dataStreams.keySet()) : names;
        for (String pattern : effective) {
            for (Map.Entry<String, Map<String, Object>> entry : dataStreams.entrySet()) {
                if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, entry.getKey()) : entry.getKey().equals(pattern)) {
                    result.add(entry.getValue());
                }
            }
        }
        return CompletableFuture.completedFuture(Map.of("data_streams", result));
    }
}
