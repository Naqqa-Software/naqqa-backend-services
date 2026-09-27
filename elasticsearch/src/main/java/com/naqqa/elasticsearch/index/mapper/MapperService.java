package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.settings.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MapperService {

    private final IndexAnalyzers indexAnalyzers;
    private final Settings indexSettings;
    private final String indexName;
    private final String tier;
    private final MappingParserContext parserContext;
    private volatile DocumentMapper documentMapper;

    public MapperService(IndexAnalyzers indexAnalyzers, Settings indexSettings, String indexName) {
        this.indexAnalyzers = indexAnalyzers;
        this.indexSettings = indexSettings;
        this.indexName = indexName;
        this.tier = indexSettings.get("index.tier", "hot");
        this.parserContext = new MappingParserContext(indexAnalyzers, indexSettings);
    }

    public IndexAnalyzers indexAnalyzers() {
        return indexAnalyzers;
    }

    public Settings indexSettings() {
        return indexSettings;
    }

    public String indexName() {
        return indexName;
    }

    public MappingParserContext parserContext() {
        return parserContext;
    }

    @SuppressWarnings("unchecked")
    public synchronized void putMapping(Map<String, Object> source) {
        JsonObject node = (JsonObject) JsonValue.wrap(source);
        RootObjectMapper newRoot = RootObjectMapper.parse(node, parserContext);
        Mapping existing = documentMapper != null ? documentMapper.mapping() : null;
        RootObjectMapper mergedRoot;
        Map<String, MetadataFieldMapper> mergedMeta;
        if (existing == null) {
            mergedRoot = newRoot;
            mergedMeta = buildDefaultMetadataMappers();
        } else {
            mergedRoot = existing.root().mergeRoot(newRoot);
            mergedMeta = new LinkedHashMap<>(existing.metadataMappers());
        }
        applyMetadataOverrides(node, mergedMeta);
        Mapping mapping = new Mapping(mergedRoot, mergedMeta);
        this.documentMapper = new DocumentMapper(mapping, parserContext, indexName, indexSettings, tier);
    }

    private Map<String, MetadataFieldMapper> buildDefaultMetadataMappers() {
        Map<String, MetadataFieldMapper> out = new LinkedHashMap<>();
        out.put(IdFieldMapper.NAME, new IdFieldMapper());
        out.put(IndexFieldMapper.NAME, new IndexFieldMapper());
        out.put(VersionFieldMapper.NAME, new VersionFieldMapper());
        out.put(SeqNoFieldMapper.NAME, new SeqNoFieldMapper());
        out.put(PrimaryTermFieldMapper.NAME, new PrimaryTermFieldMapper());
        out.put(TierFieldMapper.NAME, new TierFieldMapper());
        out.put(SourceFieldMapper.NAME, new SourceFieldMapper(true, List.of(), List.of()));
        out.put(RoutingFieldMapper.NAME, new RoutingFieldMapper(false));
        out.put(FieldNamesFieldMapper.NAME, new FieldNamesFieldMapper(true));
        out.put(IgnoredFieldMapper.NAME, new IgnoredFieldMapper());
        return out;
    }

    private void applyMetadataOverrides(JsonObject node, Map<String, MetadataFieldMapper> meta) {
        JsonObject sourceNode = node.getObject("_source");
        if (sourceNode != null) {
            boolean enabled = FieldMapper.getBool(sourceNode, "enabled", true);
            List<String> includes = readStringListOrCsv(sourceNode, "includes");
            List<String> excludes = readStringListOrCsv(sourceNode, "excludes");
            meta.put(SourceFieldMapper.NAME, new SourceFieldMapper(enabled, includes, excludes));
        }
        JsonObject routingNode = node.getObject("_routing");
        if (routingNode != null) {
            meta.put(RoutingFieldMapper.NAME, new RoutingFieldMapper(FieldMapper.getBool(routingNode, "required", false)));
        }
        JsonObject fieldNamesNode = node.getObject("_field_names");
        if (fieldNamesNode != null) {
            meta.put(FieldNamesFieldMapper.NAME, new FieldNamesFieldMapper(FieldMapper.getBool(fieldNamesNode, "enabled", true)));
        }
    }

    private static List<String> readStringListOrCsv(JsonObject node, String key) {
        JsonValue v = node.get(key);
        if (v == null || v.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (v.isArray()) {
            for (JsonValue e : v.asArray()) {
                out.add(e.asString());
            }
        } else {
            out.add(v.asString());
        }
        return out;
    }

    public DocumentMapper documentMapper() {
        return documentMapper;
    }

    public String tier() {
        return tier;
    }

    public ParsedDocument parse(String id, String routing, Map<String, Object> source) {
        if (documentMapper == null) {
            throw new IllegalStateException("no mapping has been defined for index [" + indexName + "]");
        }
        JsonObject sourceObj = (JsonObject) JsonValue.wrap(source);
        return documentMapper.parse(id, routing, sourceObj);
    }
}
