package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class RankFeaturesFieldMapper extends FieldMapper {

    public static final String TYPE = "rank_features";

    private RankFeaturesFieldMapper(String simpleName, String fullPath, JsonObject node) {
        super(simpleName, fullPath, node);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        return new RankFeaturesFieldMapper(name, fullPath, node);
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
                double weight = ((Number) e.getValue()).doubleValue();
                if (weight < 0) {
                    throw new IllegalArgumentException("rank_features field [" + fullPath + "] must contain only positive values");
                }
                out.writeDouble(weight);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        context.addIndexableField(IndexableField.binaryDocValue(fullPath, baos.toByteArray()));
    }
}
