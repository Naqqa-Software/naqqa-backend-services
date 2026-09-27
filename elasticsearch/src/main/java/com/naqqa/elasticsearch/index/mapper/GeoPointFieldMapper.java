package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;

public final class GeoPointFieldMapper extends FieldMapper {

    public static final String TYPE = "geo_point";

    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final boolean ignoreMalformed;
    private final GeoPoint nullValue;

    private GeoPointFieldMapper(String simpleName, String fullPath, JsonObject node, boolean indexed, boolean docValues,
                                 boolean stored, boolean ignoreMalformed, GeoPoint nullValue) {
        super(simpleName, fullPath, node);
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.ignoreMalformed = ignoreMalformed;
        this.nullValue = nullValue;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean indexed = getBool(node, "index", true);
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        boolean ignoreMalformed = getBool(node, "ignore_malformed", false);
        GeoPoint nullValue = node.has("null_value") && !node.get("null_value").isNull()
            ? GeoPoint.parse(node.get("null_value").toJava()) : null;
        GeoPointFieldMapper mapper = new GeoPointFieldMapper(name, fullPath, node, indexed, docValues, stored, ignoreMalformed, nullValue);
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

    @Override
    protected Object nullValue() {
        return nullValue;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        GeoPoint point = value instanceof GeoPoint gp ? gp : GeoPoint.parse(value);
        long encoded = (((long) Float.floatToIntBits((float) point.lat())) << 32) | (Float.floatToIntBits((float) point.lon()) & 0xFFFFFFFFL);
        if (docValues) {
            context.addIndexableField(IndexableField.numericDocValue(fullPath, encoded));
        }
        if (indexed) {
            byte[] latBytes = NumericUtils.doubleToSortableBytes(point.lat());
            byte[] lonBytes = NumericUtils.doubleToSortableBytes(point.lon());
            context.addIndexableField(IndexableField.point(fullPath, new byte[][] {latBytes, lonBytes}));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, (point.lat() + "," + point.lon()).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
