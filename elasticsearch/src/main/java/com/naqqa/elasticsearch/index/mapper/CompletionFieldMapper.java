package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CompletionFieldMapper extends FieldMapper {

    public static final String TYPE = "completion";

    private final boolean preserveSeparators;
    private final boolean preserveCaseOrder;
    private final int maxInputLength;

    private CompletionFieldMapper(String simpleName, String fullPath, JsonObject node, boolean preserveSeparators,
                                   boolean preserveCaseOrder, int maxInputLength) {
        super(simpleName, fullPath, node);
        this.preserveSeparators = preserveSeparators;
        this.preserveCaseOrder = preserveCaseOrder;
        this.maxInputLength = maxInputLength;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean preserveSeparators = getBool(node, "preserve_separators", true);
        boolean preserveCaseOrder = getBool(node, "preserve_position_increments", true);
        int maxInputLength = node.getInt("max_input_length", 50);
        CompletionFieldMapper mapper = new CompletionFieldMapper(name, fullPath, node, preserveSeparators, preserveCaseOrder, maxInputLength);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void parseCreateField(ParseContext context, Object value) {
        List<String> inputs = new ArrayList<>();
        long weight = 1;
        if (value instanceof Map<?, ?> map) {
            Object input = ((Map<String, Object>) map).get("input");
            if (input instanceof List<?> list) {
                for (Object o : list) {
                    inputs.add(stringValue(o));
                }
            } else if (input != null) {
                inputs.add(stringValue(input));
            }
            Object w = ((Map<String, Object>) map).get("weight");
            if (w instanceof Number n) {
                weight = n.longValue();
            }
        } else if (value instanceof List<?> list) {
            for (Object o : list) {
                inputs.add(stringValue(o));
            }
        } else {
            inputs.add(stringValue(value));
        }
        List<IndexedTerm> terms = new ArrayList<>();
        int pos = 0;
        for (String in : inputs) {
            String s = in.length() > maxInputLength ? in.substring(0, maxInputLength) : in;
            terms.add(new IndexedTerm(s, pos++, 0, s.length()));
        }
        context.addIndexableField(IndexableField.indexedText(fullPath, terms, false));
        context.addIndexableField(IndexableField.numericDocValue(fullPath, weight));
        StringBuilder sb = new StringBuilder();
        for (String in : inputs) {
            sb.append(in).append('\u0000');
        }
        context.addIndexableField(IndexableField.stored(fullPath, sb.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
