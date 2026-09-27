package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class JoinFieldMapper extends FieldMapper {

    public static final String TYPE = "join";

    private JoinFieldMapper(String simpleName, String fullPath, JsonObject node) {
        super(simpleName, fullPath, node);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        JoinFieldMapper mapper = new JoinFieldMapper(name, fullPath, node);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        String relationName;
        String parentId = null;
        if (value instanceof Map<?, ?> map) {
            relationName = String.valueOf(((Map<String, Object>) map).get("name"));
            Object parent = ((Map<String, Object>) map).get("parent");
            parentId = parent == null ? null : String.valueOf(parent);
        } else {
            relationName = stringValue(value);
        }
        context.addIndexableField(IndexableField.indexedText(fullPath, List.of(new IndexedTerm(relationName, 0, 0, relationName.length())), false));
        context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, List.of(relationName.getBytes(StandardCharsets.UTF_8))));
        if (parentId != null) {
            String parentField = fullPath + "#" + relationName;
            context.addIndexableField(IndexableField.indexedText(parentField, List.of(new IndexedTerm(parentId, 0, 0, parentId.length())), false));
            context.addIndexableField(IndexableField.sortedSetDocValues(parentField, List.of(parentId.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
