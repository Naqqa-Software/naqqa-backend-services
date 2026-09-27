package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonWriter;

import java.nio.charset.StandardCharsets;

public final class PercolatorFieldMapper extends FieldMapper {

    public static final String TYPE = "percolator";

    private PercolatorFieldMapper(String simpleName, String fullPath, JsonObject node) {
        super(simpleName, fullPath, node);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        return new PercolatorFieldMapper(name, fullPath, node);
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        String json = JsonWriter.toJson(value, false);
        context.addIndexableField(IndexableField.stored(fullPath, json.getBytes(StandardCharsets.UTF_8)));
    }
}
