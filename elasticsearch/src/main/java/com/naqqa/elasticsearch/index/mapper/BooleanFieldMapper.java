package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class BooleanFieldMapper extends FieldMapper {

    public static final String TYPE = "boolean";

    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final Boolean nullValue;

    private BooleanFieldMapper(String simpleName, String fullPath, JsonObject node, boolean indexed, boolean docValues,
                                boolean stored, Boolean nullValue) {
        super(simpleName, fullPath, node);
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.nullValue = nullValue;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean indexed = getBool(node, "index", true);
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        JsonValue nv = node.get("null_value");
        Boolean nullValue = nv == null || nv.isNull() ? null : nv.asBoolean();
        BooleanFieldMapper mapper = new BooleanFieldMapper(name, fullPath, node, indexed, docValues, stored, nullValue);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected Object nullValue() {
        return nullValue;
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if (s.equals("true")) {
                return true;
            }
            if (s.equals("false")) {
                return false;
            }
            throw new IllegalArgumentException("Cannot parse boolean value [" + s + "]");
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        throw new IllegalArgumentException("Cannot parse boolean value [" + value + "]");
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        boolean b = toBoolean(value);
        long doc = b ? 1L : 0L;
        if (indexed) {
            context.addIndexableField(IndexableField.indexedText(fullPath, List.of(new IndexedTerm(String.valueOf(b), 0, 0, 0)), false));
        }
        if (docValues) {
            context.addIndexableField(IndexableField.numericDocValue(fullPath, doc));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, String.valueOf(b).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
