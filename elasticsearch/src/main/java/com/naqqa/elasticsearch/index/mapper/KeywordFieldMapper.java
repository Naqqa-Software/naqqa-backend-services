package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public final class KeywordFieldMapper extends FieldMapper {

    public static final String TYPE = "keyword";

    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final int ignoreAbove;
    private final String nullValue;
    private final Analyzer normalizer;

    private KeywordFieldMapper(String simpleName, String fullPath, JsonObject node, boolean indexed, boolean docValues,
                                boolean stored, int ignoreAbove, String nullValue, Analyzer normalizer) {
        super(simpleName, fullPath, node);
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.ignoreAbove = ignoreAbove;
        this.nullValue = nullValue;
        this.normalizer = normalizer;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean indexed = getBool(node, "index", true);
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        int ignoreAbove = node.getInt("ignore_above", Integer.MAX_VALUE);
        String nullValue = node.getString("null_value", null);
        String normalizerName = node.getString("normalizer", null);
        Analyzer normalizer = normalizerName != null ? ctx.analyzers().getNormalizer(normalizerName) : null;
        KeywordFieldMapper mapper = new KeywordFieldMapper(name, fullPath, node, indexed, docValues, stored, ignoreAbove, nullValue, normalizer);
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
        if (normalizer != null) {
            s = normalizer.normalize(fullPath, s);
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (indexed) {
            context.addIndexableField(IndexableField.indexedText(fullPath, List.of(new IndexedTerm(s, 0, 0, s.length())), false));
        }
        if (docValues) {
            context.addIndexableField(IndexableField.sortedSetDocValues(fullPath, List.of(bytes)));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, bytes));
        }
    }

    @Override
    protected Set<String> updatableParameters() {
        return Set.of("meta", "ignore_above");
    }
}
