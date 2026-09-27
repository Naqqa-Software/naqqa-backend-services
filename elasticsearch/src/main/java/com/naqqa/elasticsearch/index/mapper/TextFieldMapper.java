package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.common.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class TextFieldMapper extends FieldMapper {

    public static final String TYPE = "text";

    private final boolean indexed;
    private final boolean stored;
    private final boolean norms;
    private final Analyzer analyzer;
    private final Analyzer searchAnalyzer;
    private int termVectorFlags;

    private TextFieldMapper(String simpleName, String fullPath, JsonObject node, boolean indexed, boolean stored,
                             boolean norms, Analyzer analyzer, Analyzer searchAnalyzer) {
        super(simpleName, fullPath, node);
        this.indexed = indexed;
        this.stored = stored;
        this.norms = norms;
        this.analyzer = analyzer;
        this.searchAnalyzer = searchAnalyzer;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean indexed = getBool(node, "index", true);
        boolean stored = getBool(node, "store", false);
        boolean norms = getBool(node, "norms", true);
        String analyzerName = node.getString("analyzer", null);
        String searchAnalyzerName = node.getString("search_analyzer", analyzerName);
        Analyzer analyzer = analyzerName != null ? ctx.analyzers().get(analyzerName) : ctx.analyzers().defaultAnalyzer();
        Analyzer searchAnalyzer = searchAnalyzerName != null ? ctx.analyzers().get(searchAnalyzerName) : ctx.analyzers().defaultSearchAnalyzer();
        TextFieldMapper mapper = new TextFieldMapper(name, fullPath, node, indexed, stored, norms, analyzer, searchAnalyzer);
        mapper.termVectorFlags = parseTermVector(fullPath, node.getString("term_vector", "no"));
        mapper.multiFields.putAll(parseMultiFields(fullPath, node, ctx, depth));
        return mapper;
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    static int parseTermVector(String fullPath, String value) {
        return switch (value) {
            case "no" -> 0;
            case "yes" -> IndexableField.TERM_VECTORS;
            case "with_positions", "with_positions_payloads" -> IndexableField.TERM_VECTORS | IndexableField.TERM_VECTOR_POSITIONS;
            case "with_offsets" -> IndexableField.TERM_VECTORS | IndexableField.TERM_VECTOR_OFFSETS;
            case "with_positions_offsets", "with_positions_offsets_payloads" ->
                IndexableField.TERM_VECTORS | IndexableField.TERM_VECTOR_POSITIONS | IndexableField.TERM_VECTOR_OFFSETS;
            default -> throw new IllegalArgumentException("Unknown value [" + value + "] for field [term_vector] of field [" + fullPath + "]");
        };
    }

    public int termVectorFlags() {
        return termVectorFlags;
    }

    public Analyzer searchAnalyzer() {
        return searchAnalyzer;
    }

    static List<IndexedTerm> tokenize(Analyzer analyzer, String fieldName, String text) {
        List<IndexedTerm> terms = new ArrayList<>();
        TokenStream ts = analyzer.tokenStream(fieldName, text);
        int position = -1;
        try {
            ts.reset();
            while (ts.incrementToken()) {
                Token t = ts.token();
                position += t.positionIncrement();
                terms.add(new IndexedTerm(t.term(), Math.max(position, 0), t.startOffset(), t.endOffset()));
            }
            ts.end();
        } finally {
            ts.close();
        }
        return terms;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        String text = stringValue(value);
        if (indexed) {
            List<IndexedTerm> terms = tokenize(analyzer, fullPath, text);
            context.addIndexableField(IndexableField.indexedText(fullPath, terms, norms, termVectorFlags));
        }
        if (stored) {
            context.addIndexableField(IndexableField.stored(fullPath, text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    protected Set<String> updatableParameters() {
        return Set.of("meta", "search_analyzer");
    }
}
