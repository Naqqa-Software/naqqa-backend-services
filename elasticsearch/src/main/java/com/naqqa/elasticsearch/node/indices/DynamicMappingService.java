package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.index.mapper.Mapper;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.mapper.Mapping;
import com.naqqa.elasticsearch.index.mapper.ParsedDocument;
import com.naqqa.elasticsearch.node.support.SettingsMaps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DynamicMappingService {

    private final IndicesService indicesService;
    private final MetadataIndexService metadataService;
    private final Object lock = new Object();

    public DynamicMappingService(IndicesService indicesService, MetadataIndexService metadataService) {
        this.indicesService = indicesService;
        this.metadataService = metadataService;
    }

    public void onDocument(String index, String id, String routing, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        IndexService service = indicesService.indexService(index);
        if (service == null) {
            return;
        }
        MapperService mapperService = service.mapperService();
        if (mapperService.documentMapper() == null || !hasUnmappedLeaf("", source, mapperService.documentMapper().mapping())) {
            return;
        }
        ParsedDocument parsed;
        try {
            parsed = mapperService.parse(id == null ? "_dynamic_probe" : id, routing, source);
        } catch (RuntimeException e) {
            return;
        }
        if (!parsed.hasDynamicMappingUpdate()) {
            return;
        }
        Map<String, Object> update = new LinkedHashMap<>();
        for (Mapper mapper : parsed.dynamicMappingUpdate()) {
            JsonObject def = new JsonObject();
            mapper.toMapping(def);
            Map<String, Object> defMap = SettingsMaps.asMap(def.toJava());
            if (defMap == null) {
                continue;
            }
            insert(update, mapper.fullPath().split("\\."), defMap);
        }
        if (update.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (!hasUnmappedLeaf("", source, mapperService.documentMapper().mapping())) {
                return;
            }
            try {
                metadataService.putMapping(List.of(index), update);
            } catch (RuntimeException e) {
                System.err.println("[mapping] failed to apply dynamic mapping update to [" + index + "]: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> root, String[] path, Map<String, Object> def) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.length - 1; i++) {
            Map<String, Object> props = (Map<String, Object>) current.computeIfAbsent("properties", k -> new LinkedHashMap<String, Object>());
            Object existing = props.get(path[i]);
            Map<String, Object> next;
            if (existing instanceof Map<?, ?> m) {
                next = (Map<String, Object>) m;
            } else {
                next = new LinkedHashMap<>();
                props.put(path[i], next);
            }
            current = next;
        }
        Map<String, Object> props = (Map<String, Object>) current.computeIfAbsent("properties", k -> new LinkedHashMap<String, Object>());
        Object existing = props.get(path[path.length - 1]);
        if (existing instanceof Map<?, ?> m && m.containsKey("properties")) {
            Map<String, Object> merged = new LinkedHashMap<>(def);
            merged.put("properties", m.get("properties"));
            props.put(path[path.length - 1], merged);
        } else if (existing == null) {
            props.put(path[path.length - 1], new LinkedHashMap<>(def));
        }
    }

    private static boolean hasUnmappedLeaf(String prefix, Map<String, Object> source, Mapping mapping) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map<?, ?> m) {
                if (mapping.fieldMapper(path) != null) {
                    continue;
                }
                Map<String, Object> child = SettingsMaps.asMap(m);
                if (child != null && hasUnmappedLeaf(path, child, mapping)) {
                    return true;
                }
            } else if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?>) {
                for (Object element : list) {
                    Map<String, Object> child = SettingsMaps.asMap(element);
                    if (child != null && hasUnmappedLeaf(path, child, mapping)) {
                        return true;
                    }
                }
            } else if (value != null && !(value instanceof List<?> l && l.isEmpty())) {
                if (mapping.fieldMapper(path) == null) {
                    return true;
                }
            }
        }
        return false;
    }
}
