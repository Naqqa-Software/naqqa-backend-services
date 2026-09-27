package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ConstantKeywordFieldMapper extends FieldMapper {

    public static final String TYPE = "constant_keyword";

    private final String value;

    private ConstantKeywordFieldMapper(String simpleName, String fullPath, JsonObject node, String value) {
        super(simpleName, fullPath, node);
        this.value = value;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        String value = node.getString("value", null);
        return new ConstantKeywordFieldMapper(name, fullPath, node, value);
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    public String constantValue() {
        return value;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object docValue) {
        String provided = stringValue(docValue);
        if (value != null && !value.equals(provided)) {
            throw new IllegalArgumentException("[constant_keyword] field [" + fullPath + "] only accepts values that are equal to the value defined in the mapping [" + value + "], but got [" + provided + "]");
        }
        String actual = value != null ? value : provided;
        context.addIndexableField(IndexableField.indexedText(fullPath, List.of(new IndexedTerm(actual, 0, 0, actual.length())), false));
        context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, List.of(actual.getBytes(StandardCharsets.UTF_8))));
    }
}
