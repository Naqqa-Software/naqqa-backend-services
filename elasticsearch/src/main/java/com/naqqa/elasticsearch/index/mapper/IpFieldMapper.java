package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.network.InetAddresses;

import java.net.InetAddress;

public final class IpFieldMapper extends FieldMapper {

    public static final String TYPE = "ip";

    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final boolean ignoreMalformed;
    private final String nullValue;

    private IpFieldMapper(String simpleName, String fullPath, JsonObject node, boolean indexed, boolean docValues,
                           boolean stored, boolean ignoreMalformed, String nullValue) {
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
        JsonValue nv = node.get("null_value");
        String nullValue = nv == null || nv.isNull() ? null : nv.asString();
        IpFieldMapper mapper = new IpFieldMapper(name, fullPath, node, indexed, docValues, stored, ignoreMalformed, nullValue);
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
        String s = stringValue(value);
        InetAddress addr = InetAddresses.forString(s);
        byte[] bytes = InetAddresses.toBytes(addr);
        if (docValues) {
            context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, java.util.List.of(bytes)));
        }
        if (indexed) {
            context.addIndexableField(IndexableField.point(fullPath, new byte[][] {bytes}));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, InetAddresses.toAddrString(addr).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
