package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.geo.format.GeoJson;
import com.naqqa.elasticsearch.common.geo.format.WellKnownText;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class GeoShapeFieldMapper extends FieldMapper {

    public static final String TYPE = "geo_shape";

    protected final boolean docValues;
    protected final boolean stored;
    protected final boolean ignoreMalformed;

    protected GeoShapeFieldMapper(String simpleName, String fullPath, JsonObject node, boolean docValues, boolean stored, boolean ignoreMalformed) {
        super(simpleName, fullPath, node);
        this.docValues = docValues;
        this.stored = stored;
        this.ignoreMalformed = ignoreMalformed;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        boolean ignoreMalformed = getBool(node, "ignore_malformed", false);
        GeoShapeFieldMapper mapper = new GeoShapeFieldMapper(name, fullPath, node, docValues, stored, ignoreMalformed);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected boolean ignoreMalformed() {
        return ignoreMalformed;
    }

    @SuppressWarnings("unchecked")
    static Geometry parseGeometry(Object value) {
        if (value instanceof Map<?, ?> map) {
            return GeoJson.fromMap((Map<String, Object>) map);
        }
        if (value instanceof String s) {
            return WellKnownText.fromWKT(s.trim());
        }
        throw new IllegalArgumentException("cannot parse shape from [" + value + "]");
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        Geometry geometry = parseGeometry(value);
        String wkt = WellKnownText.toWKT(geometry);
        if (docValues) {
            context.addIndexableField(IndexableField.binaryDocValue(fullPath, wkt.getBytes(StandardCharsets.UTF_8)));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, wkt.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
