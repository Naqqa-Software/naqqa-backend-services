package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class VersionStringFieldMapper extends FieldMapper {

    public static final String TYPE = "version";

    private final boolean docValues;
    private final boolean stored;

    private VersionStringFieldMapper(String simpleName, String fullPath, JsonObject node, boolean docValues, boolean stored) {
        super(simpleName, fullPath, node);
        this.docValues = docValues;
        this.stored = stored;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        VersionStringFieldMapper mapper = new VersionStringFieldMapper(name, fullPath, node, docValues, stored);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        String s = stringValue(value);
        context.addIndexableField(IndexableField.indexedText(fullPath, List.of(new IndexedTerm(s, 0, 0, s.length())), false));
        if (docValues) {
            context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, List.of(s.getBytes(StandardCharsets.UTF_8))));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, s.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
