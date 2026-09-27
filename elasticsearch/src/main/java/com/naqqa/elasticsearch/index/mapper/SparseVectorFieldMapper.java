package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class SparseVectorFieldMapper extends FieldMapper {

    public static final String TYPE = "sparse_vector";

    private SparseVectorFieldMapper(String simpleName, String fullPath, JsonObject node) {
        super(simpleName, fullPath, node);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        return new SparseVectorFieldMapper(name, fullPath, node);
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        Map<String, Object> map = (Map<String, Object>) value;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(map.size());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                byte[] nameBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.writeFloat(((Number) e.getValue()).floatValue());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        context.addIndexableField(IndexableField.binaryDocValue(fullPath, baos.toByteArray()));
    }
}
