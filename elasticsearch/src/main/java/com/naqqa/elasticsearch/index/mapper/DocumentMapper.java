package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.settings.Settings;

public final class DocumentMapper {

    private final Mapping mapping;
    private final MappingParserContext parserContext;
    private final String indexName;
    private final Settings indexSettings;
    private final String tier;

    public DocumentMapper(Mapping mapping, MappingParserContext parserContext, String indexName, Settings indexSettings, String tier) {
        this.mapping = mapping;
        this.parserContext = parserContext;
        this.indexName = indexName;
        this.indexSettings = indexSettings;
        this.tier = tier;
    }

    public Mapping mapping() {
        return mapping;
    }

    public MappingParserContext parserContext() {
        return parserContext;
    }

    public String indexName() {
        return indexName;
    }

    public Settings indexSettings() {
        return indexSettings;
    }

    public String tier() {
        return tier;
    }

    public ParsedDocument parse(String id, String routing, JsonObject source) {
        return DocumentParser.parse(this, id, routing, source);
    }

    public JsonObject mappingSource() {
        return mapping.toMapping();
    }
}
