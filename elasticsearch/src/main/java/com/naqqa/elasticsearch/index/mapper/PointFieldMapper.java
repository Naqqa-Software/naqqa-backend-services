package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class PointFieldMapper extends FieldMapper {

    public static final String TYPE = "point";

    private final boolean docValues;
    private final boolean stored;
    private final boolean ignoreMalformed;

    private PointFieldMapper(String simpleName, String fullPath, JsonObject node, boolean docValues, boolean stored, boolean ignoreMalformed) {
        super(simpleName, fullPath, node);
        this.docValues = docValues;
        this.stored = stored;
        this.ignoreMalformed = ignoreMalformed;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        boolean ignoreMalformed = getBool(node, "ignore_malformed", false);
        PointFieldMapper mapper = new PointFieldMapper(name, fullPath, node, docValues, stored, ignoreMalformed);
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
    static double[] parseXY(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object x = map.get("x");
            Object y = map.get("y");
            if (x == null || y == null) {
                throw new IllegalArgumentException("point must contain 'x' and 'y'");
            }
            return new double[] {toDouble(x), toDouble(y)};
        }
        if (value instanceof List<?> list) {
            if (list.size() != 2) {
                throw new IllegalArgumentException("point array must have 2 elements [x, y]");
            }
            return new double[] {toDouble(list.get(0)), toDouble(list.get(1))};
        }
        if (value instanceof String s) {
            s = s.trim();
            if (s.toUpperCase(java.util.Locale.ROOT).startsWith("POINT")) {
                com.naqqa.elasticsearch.common.geo.geometry.Geometry g = com.naqqa.elasticsearch.common.geo.format.WellKnownText.fromWKT(s);
                if (g instanceof com.naqqa.elasticsearch.common.geo.geometry.Point p) {
                    return new double[] {p.getX(), p.getY()};
                }
                throw new IllegalArgumentException("expected WKT POINT");
            }
            String[] parts = s.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("could not parse point [" + s + "]");
            }
            return new double[] {Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        }
        throw new IllegalArgumentException("cannot parse point from [" + value + "]");
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o));
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        double[] xy = parseXY(value);
        if (docValues) {
            long encoded = (((long) Float.floatToIntBits((float) xy[0])) << 32) | (Float.floatToIntBits((float) xy[1]) & 0xFFFFFFFFL);
            context.addIndexableField(IndexableField.numericDocValue(fullPath, encoded));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, (xy[0] + "," + xy[1]).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
