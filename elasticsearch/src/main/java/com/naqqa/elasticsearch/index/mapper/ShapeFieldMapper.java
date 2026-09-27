package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

public final class ShapeFieldMapper extends GeoShapeFieldMapper {

    public static final String TYPE = "shape";

    private ShapeFieldMapper(String simpleName, String fullPath, JsonObject node, boolean docValues, boolean stored, boolean ignoreMalformed) {
        super(simpleName, fullPath, node, docValues, stored, ignoreMalformed);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        boolean ignoreMalformed = getBool(node, "ignore_malformed", false);
        ShapeFieldMapper mapper = new ShapeFieldMapper(name, fullPath, node, docValues, stored, ignoreMalformed);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }
}
