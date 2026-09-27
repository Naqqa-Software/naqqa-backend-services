package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class HistogramFieldMapper extends FieldMapper {

    public static final String TYPE = "histogram";

    private HistogramFieldMapper(String simpleName, String fullPath, JsonObject node) {
        super(simpleName, fullPath, node);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        return new HistogramFieldMapper(name, fullPath, node);
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        Map<String, Object> map = (Map<String, Object>) value;
        List<Number> values = (List<Number>) map.get("values");
        List<Number> counts = (List<Number>) map.get("counts");
        if (values == null || counts == null || values.size() != counts.size()) {
            throw new IllegalArgumentException("histogram field [" + fullPath + "] requires equal-length [values] and [counts]");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(values.size());
            for (int i = 0; i < values.size(); i++) {
                out.writeDouble(values.get(i).doubleValue());
                out.writeLong(counts.get(i).longValue());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        context.addIndexableField(IndexableField.binaryDocValue(fullPath, baos.toByteArray()));
    }
}
