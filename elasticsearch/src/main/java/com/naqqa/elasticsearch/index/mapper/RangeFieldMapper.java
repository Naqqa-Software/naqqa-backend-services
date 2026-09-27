package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.network.InetAddresses;
import com.naqqa.elasticsearch.common.time.DateFormatter;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class RangeFieldMapper extends FieldMapper {

    public enum RangeType {
        INTEGER, LONG, FLOAT, DOUBLE, DATE, IP;

        public String typeName() {
            return switch (this) {
                case INTEGER -> "integer_range";
                case LONG -> "long_range";
                case FLOAT -> "float_range";
                case DOUBLE -> "double_range";
                case DATE -> "date_range";
                case IP -> "ip_range";
            };
        }
    }

    private final RangeType rangeType;
    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final DateFormatter formatter;

    private RangeFieldMapper(String simpleName, String fullPath, JsonObject node, RangeType rangeType, boolean indexed,
                              boolean docValues, boolean stored, DateFormatter formatter) {
        super(simpleName, fullPath, node);
        this.rangeType = rangeType;
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.formatter = formatter;
    }

    public static TypeParser typeParser(RangeType type) {
        return (name, fullPath, node, ctx, depth) -> {
            boolean indexed = getBool(node, "index", true);
            boolean docValues = getBool(node, "doc_values", true);
            boolean stored = getBool(node, "store", false);
            DateFormatter formatter = type == RangeType.DATE
                ? DateFormatter.forPattern(node.getString("format", "strict_date_optional_time||epoch_millis"))
                : null;
            RangeFieldMapper mapper = new RangeFieldMapper(name, fullPath, node, type, indexed, docValues, stored, formatter);
            mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
            return mapper;
        };
    }

    @Override
    public String typeName() {
        return rangeType.typeName();
    }

    private byte[] encodeBound(Object bound) {
        return switch (rangeType) {
            case INTEGER -> NumericUtils.intToSortableBytes(((Number) coerceNumber(bound)).intValue());
            case LONG -> NumericUtils.longToSortableBytes(((Number) coerceNumber(bound)).longValue());
            case FLOAT -> NumericUtils.floatToSortableBytes(((Number) coerceNumber(bound)).floatValue());
            case DOUBLE -> NumericUtils.doubleToSortableBytes(((Number) coerceNumber(bound)).doubleValue());
            case DATE -> NumericUtils.longToSortableBytes(bound instanceof Number n ? n.longValue() : formatter.parseMillis(String.valueOf(bound)));
            case IP -> {
                InetAddress addr = InetAddresses.forString(String.valueOf(bound));
                yield InetAddresses.toBytes(addr);
            }
        };
    }

    private String boundToString(Object bound) {
        if (rangeType == RangeType.IP) {
            return InetAddresses.toAddrString(InetAddresses.forString(String.valueOf(bound)));
        }
        return String.valueOf(bound);
    }

    private static Object coerceNumber(Object bound) {
        if (bound instanceof Number) {
            return bound;
        }
        return Double.parseDouble(String.valueOf(bound));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        Map<String, Object> map = (Map<String, Object>) value;
        Object lower = map.containsKey("gte") ? map.get("gte") : map.get("gt");
        Object upper = map.containsKey("lte") ? map.get("lte") : map.get("lt");
        if (lower == null && upper == null) {
            throw new IllegalArgumentException("range field [" + fullPath + "] requires at least one bound");
        }
        byte[] minBytes = lower != null ? encodeBound(lower) : encodeBound(upper);
        byte[] maxBytes = upper != null ? encodeBound(upper) : encodeBound(lower);
        if (indexed) {
            context.addIndexableField(IndexableField.point(fullPath, new byte[][] {minBytes, maxBytes}));
        }
        if (docValues) {
            byte[] combined = new byte[minBytes.length + maxBytes.length];
            System.arraycopy(minBytes, 0, combined, 0, minBytes.length);
            System.arraycopy(maxBytes, 0, combined, minBytes.length, maxBytes.length);
            context.addIndexableField(IndexableField.binaryDocValue(fullPath, combined));
        }
        if (stored) {
            String s = "[" + (lower != null ? boundToString(lower) : "*") + "," + (upper != null ? boundToString(upper) : "*") + "]";
            context.addIndexableField(IndexableField.stored(fullPath, s.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
