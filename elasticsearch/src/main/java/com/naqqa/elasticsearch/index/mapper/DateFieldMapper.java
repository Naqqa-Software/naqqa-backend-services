package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.time.DateFormatter;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class DateFieldMapper extends FieldMapper {

    public static final String TYPE = "date";
    public static final String NANOS_TYPE = "date_nanos";
    private static final String DEFAULT_FORMAT = "strict_date_optional_time||epoch_millis";

    private final boolean nanos;
    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final boolean ignoreMalformed;
    private final String nullValue;
    private final DateFormatter formatter;

    private DateFieldMapper(String simpleName, String fullPath, JsonObject node, boolean nanos, boolean indexed,
                             boolean docValues, boolean stored, boolean ignoreMalformed, String nullValue, DateFormatter formatter) {
        super(simpleName, fullPath, node);
        this.nanos = nanos;
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.ignoreMalformed = ignoreMalformed;
        this.nullValue = nullValue;
        this.formatter = formatter;
    }

    public static TypeParser typeParser(boolean nanos) {
        return (name, fullPath, node, ctx, depth) -> {
            boolean indexed = getBool(node, "index", true);
            boolean docValues = getBool(node, "doc_values", true);
            boolean stored = getBool(node, "store", false);
            boolean ignoreMalformed = getBool(node, "ignore_malformed", false);
            String format = node.getString("format", DEFAULT_FORMAT);
            JsonValue nv = node.get("null_value");
            String nullValue = nv == null || nv.isNull() ? null : nv.asString();
            DateFormatter formatter = DateFormatter.forPattern(format);
            DateFieldMapper mapper = new DateFieldMapper(name, fullPath, node, nanos, indexed, docValues, stored, ignoreMalformed, nullValue, formatter);
            mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
            return mapper;
        };
    }

    @Override
    public String typeName() {
        return nanos ? NANOS_TYPE : TYPE;
    }

    @Override
    protected boolean ignoreMalformed() {
        return ignoreMalformed;
    }

    @Override
    protected Object nullValue() {
        return nullValue;
    }

    private long parseValue(Object value) {
        if (value instanceof Number n) {
            return nanos ? n.longValue() : n.longValue();
        }
        String s = stringValue(value);
        return nanos ? formatter.parseNanos(s) : formatter.parseMillis(s);
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        long v = parseValue(value);
        if (docValues) {
            context.addIndexableField(IndexableField.numericDocValue(fullPath, v));
        }
        if (indexed) {
            context.addIndexableField(IndexableField.point(fullPath, new byte[][] {NumericUtils.longToSortableBytes(v)}));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, String.valueOf(v).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    protected Set<String> updatableParameters() {
        return Set.of("meta", "ignore_malformed");
    }
}
