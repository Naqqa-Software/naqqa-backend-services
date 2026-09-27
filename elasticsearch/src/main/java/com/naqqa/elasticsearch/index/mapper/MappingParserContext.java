package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.common.exception.MapperParsingException;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.settings.Settings;

import java.util.Map;

public final class MappingParserContext {

    private final IndexAnalyzers indexAnalyzers;
    private final Settings indexSettings;
    private final Map<String, TypeParser> typeParsers;
    private final int maxTotalFields;
    private final int maxDepth;
    private final int maxNestedFields;
    private int totalFieldCount;
    private int nestedFieldCount;

    public MappingParserContext(IndexAnalyzers indexAnalyzers, Settings indexSettings) {
        this(indexAnalyzers, indexSettings, TypeParsers.builtin());
    }

    public MappingParserContext(IndexAnalyzers indexAnalyzers, Settings indexSettings, Map<String, TypeParser> typeParsers) {
        this.indexAnalyzers = indexAnalyzers;
        this.indexSettings = indexSettings;
        this.typeParsers = typeParsers;
        this.maxTotalFields = indexSettings.getAsInt("index.mapping.total_fields.limit", 1000);
        this.maxDepth = indexSettings.getAsInt("index.mapping.depth.limit", 20);
        this.maxNestedFields = indexSettings.getAsInt("index.mapping.nested_fields.limit", 50);
    }

    public IndexAnalyzers analyzers() {
        return indexAnalyzers;
    }

    public Settings indexSettings() {
        return indexSettings;
    }

    public int totalFieldCount() {
        return totalFieldCount;
    }

    public Mapper parseMapper(String name, String fullPath, JsonObject node, int depth) {
        if (depth > maxDepth) {
            throw new IllegalArgumentException("Limit of mapping depth [" + maxDepth + "] has been exceeded due to object field [" + fullPath + "]");
        }
        String type = node.getString("type");
        if (type == null) {
            type = "object";
        }
        TypeParser parser = typeParsers.get(type);
        if (parser == null) {
            throw new MapperParsingException("No handler for type [{}] declared on field [{}]", type, fullPath);
        }
        if (type.equals("nested")) {
            nestedFieldCount++;
            if (nestedFieldCount > maxNestedFields) {
                throw new IllegalArgumentException("Limit of nested fields [" + maxNestedFields + "] has been exceeded");
            }
        } else if (!type.equals("object")) {
            totalFieldCount++;
            if (totalFieldCount > maxTotalFields) {
                throw new IllegalArgumentException("Limit of total fields [" + maxTotalFields + "] has been exceeded");
            }
        }
        return parser.parse(name, fullPath, node, this, depth);
    }

    public FieldMapper parseField(String name, String fullPath, JsonObject node, int depth) {
        Mapper mapper = parseMapper(name, fullPath, node, depth);
        if (!(mapper instanceof FieldMapper fieldMapper)) {
            throw new MapperParsingException("Multi-field [{}] must not be of type [object] or [nested]", name);
        }
        return fieldMapper;
    }
}
