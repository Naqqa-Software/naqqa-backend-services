package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlattenedFieldMapper extends FieldMapper {

    public static final String TYPE = "flattened";

    private final boolean indexed;
    private final boolean docValues;
    private final int depthLimit;

    private FlattenedFieldMapper(String simpleName, String fullPath, JsonObject node, boolean indexed, boolean docValues, int depthLimit) {
        super(simpleName, fullPath, node);
        this.indexed = indexed;
        this.docValues = docValues;
        this.depthLimit = depthLimit;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean indexed = getBool(node, "index", true);
        boolean docValues = getBool(node, "doc_values", true);
        int depthLimit = node.getInt("depth_limit", 20);
        FlattenedFieldMapper mapper = new FlattenedFieldMapper(name, fullPath, node, indexed, docValues, depthLimit);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @SuppressWarnings("unchecked")
    private void collect(String path, Object value, int depth, List<String> keyed, List<String> plain) {
        if (depth > depthLimit) {
            throw new IllegalArgumentException("flattened field [" + fullPath + "] exceeds depth limit [" + depthLimit + "]");
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String childPath = path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey();
                collect(childPath, e.getValue(), depth + 1, keyed, plain);
            }
        } else if (value instanceof List<?> list) {
            for (Object o : list) {
                collect(path, o, depth, keyed, plain);
            }
        } else if (value != null) {
            String s = stringValue(value);
            keyed.add(path + "\u0000" + s);
            plain.add(s);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        List<String> keyed = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        collect("", value, 0, keyed, plain);
        if (indexed) {
            List<IndexedTerm> terms = new ArrayList<>();
            int pos = 0;
            for (String p : plain) {
                terms.add(new IndexedTerm(p, pos++, 0, p.length()));
            }
            for (String k : keyed) {
                terms.add(new IndexedTerm(k, pos++, 0, k.length()));
            }
            context.addIndexableField(IndexableField.indexedText(fullPath, terms, false));
        }
        if (docValues) {
            List<byte[]> values = new ArrayList<>();
            for (String k : keyed) {
                values.add(k.getBytes(StandardCharsets.UTF_8));
            }
            context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, values));
        }
    }
}
