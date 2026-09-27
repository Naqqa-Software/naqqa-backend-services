package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.List;

public final class TokenCountFieldMapper extends FieldMapper {

    public static final String TYPE = "token_count";

    private final Analyzer analyzer;
    private final boolean indexed;
    private final boolean docValues;
    private final boolean stored;
    private final boolean enablePositionIncrements;

    private TokenCountFieldMapper(String simpleName, String fullPath, JsonObject node, Analyzer analyzer, boolean indexed,
                                   boolean docValues, boolean stored, boolean enablePositionIncrements) {
        super(simpleName, fullPath, node);
        this.analyzer = analyzer;
        this.indexed = indexed;
        this.docValues = docValues;
        this.stored = stored;
        this.enablePositionIncrements = enablePositionIncrements;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        String analyzerName = node.getString("analyzer", null);
        Analyzer analyzer = analyzerName != null ? ctx.analyzers().get(analyzerName) : ctx.analyzers().defaultAnalyzer();
        boolean indexed = getBool(node, "index", true);
        boolean docValues = getBool(node, "doc_values", true);
        boolean stored = getBool(node, "store", false);
        boolean enablePositionIncrements = getBool(node, "enable_position_increments", true);
        TokenCountFieldMapper mapper = new TokenCountFieldMapper(name, fullPath, node, analyzer, indexed, docValues, stored, enablePositionIncrements);
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        String text = stringValue(value);
        List<IndexedTerm> terms = TextFieldMapper.tokenize(analyzer, fullPath, text);
        int count = enablePositionIncrements && !terms.isEmpty() ? terms.get(terms.size() - 1).position() + 1 : terms.size();
        if (docValues) {
            context.addIndexableField(IndexableField.numericDocValue(fullPath, count));
        }
        if (indexed) {
            context.addIndexableField(IndexableField.point(fullPath, new byte[][] {NumericUtils.intToSortableBytes(count)}));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, String.valueOf(count).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
