package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class WildcardFieldMapper extends FieldMapper {

    public static final String TYPE = "wildcard";
    private static final int NGRAM_SIZE = 3;

    private final boolean docValues;
    private final int ignoreAbove;
    private final String nullValue;

    private WildcardFieldMapper(String simpleName, String fullPath, JsonObject node, boolean docValues, int ignoreAbove, String nullValue) {
        super(simpleName, fullPath, node);
        this.docValues = docValues;
        this.ignoreAbove = ignoreAbove;
        this.nullValue = nullValue;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean docValues = getBool(node, "doc_values", true);
        int ignoreAbove = node.getInt("ignore_above", Integer.MAX_VALUE);
        String nullValue = node.getString("null_value", null);
        WildcardFieldMapper mapper = new WildcardFieldMapper(name, fullPath, node, docValues, ignoreAbove, nullValue);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected Object nullValue() {
        return nullValue;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        String s = stringValue(value);
        if (s.length() > ignoreAbove) {
            context.addIgnoredField(fullPath);
            return;
        }
        List<IndexedTerm> terms = new ArrayList<>();
        if (s.length() < NGRAM_SIZE) {
            terms.add(new IndexedTerm(s, 0, 0, s.length()));
        } else {
            for (int i = 0; i <= s.length() - NGRAM_SIZE; i++) {
                terms.add(new IndexedTerm(s.substring(i, i + NGRAM_SIZE), i, i, i + NGRAM_SIZE));
            }
        }
        context.addIndexableField(IndexableField.indexedText(fullPath, terms, false));
        if (docValues) {
            context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, List.of(s.getBytes(StandardCharsets.UTF_8))));
        }
    }
}
