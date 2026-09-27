package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.List;
import java.util.Set;

public final class DenseVectorFieldMapper extends FieldMapper {

    public static final String TYPE = "dense_vector";

    private final int dims;
    private final String similarity;
    private final String elementType;

    private DenseVectorFieldMapper(String simpleName, String fullPath, JsonObject node, int dims, String similarity, String elementType) {
        super(simpleName, fullPath, node);
        this.dims = dims;
        this.similarity = similarity;
        this.elementType = elementType;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        int dims = node.getInt("dims", -1);
        String similarity = node.getString("similarity", "cosine");
        String elementType = node.getString("element_type", "float");
        if (!Set.of("cosine", "dot_product", "l2_norm", "max_inner_product").contains(similarity)) {
            throw new IllegalArgumentException("dense_vector field [" + fullPath + "] has unsupported similarity [" + similarity + "]");
        }
        if (!Set.of("float", "byte", "bit").contains(elementType)) {
            throw new IllegalArgumentException("dense_vector field [" + fullPath + "] has unsupported element_type [" + elementType + "]");
        }
        return new DenseVectorFieldMapper(name, fullPath, node, dims, similarity, elementType);
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    public int dims() {
        return dims;
    }

    @Override
    protected boolean parsesArrayAsSingleValue() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        List<Number> list = (List<Number>) value;
        if (dims > 0 && list.size() != dims) {
            throw new IllegalArgumentException("dense_vector field [" + fullPath + "] expects [" + dims + "] values but got [" + list.size() + "]");
        }
        float[] floats = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            floats[i] = list.get(i).floatValue();
        }
        byte[] raw;
        if (elementType.equals("float")) {
            raw = new byte[floats.length * 4];
            for (int i = 0; i < floats.length; i++) {
                int bits = Float.floatToIntBits(floats[i]);
                raw[i * 4] = (byte) (bits >>> 24);
                raw[i * 4 + 1] = (byte) (bits >>> 16);
                raw[i * 4 + 2] = (byte) (bits >>> 8);
                raw[i * 4 + 3] = (byte) bits;
            }
        } else {
            raw = new byte[floats.length];
            for (int i = 0; i < floats.length; i++) {
                raw[i] = (byte) list.get(i).intValue();
            }
        }
        context.addIndexableField(IndexableField.vector(fullPath, floats, raw, elementType, list.size(), similarity));
    }
}
