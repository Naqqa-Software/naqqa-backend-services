package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.Base64;

public final class BinaryFieldMapper extends FieldMapper {

    public static final String TYPE = "binary";

    private final boolean docValues;
    private final boolean stored;

    private BinaryFieldMapper(String simpleName, String fullPath, JsonObject node, boolean docValues, boolean stored) {
        super(simpleName, fullPath, node);
        this.docValues = docValues;
        this.stored = stored;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean docValues = getBool(node, "doc_values", false);
        boolean stored = getBool(node, "store", false);
        BinaryFieldMapper mapper = new BinaryFieldMapper(name, fullPath, node, docValues, stored);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        byte[] bytes = Base64.getDecoder().decode(stringValue(value));
        if (docValues) {
            context.addIndexableField(IndexableField.binaryDocValue(fullPath, bytes));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, bytes));
        }
    }
}
