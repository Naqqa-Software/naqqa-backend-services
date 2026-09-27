package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.List;

public final class SearchAsYouTypeFieldMapper extends FieldMapper {

    public static final String TYPE = "search_as_you_type";
    private static final String[] SHINGLE_SUFFIXES = {"_2gram", "_3gram", "_index_prefix"};

    private final Analyzer analyzer;
    private final boolean norms;

    private SearchAsYouTypeFieldMapper(String simpleName, String fullPath, JsonObject node, Analyzer analyzer, boolean norms) {
        super(simpleName, fullPath, node);
        this.analyzer = analyzer;
        this.norms = norms;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        String analyzerName = node.getString("analyzer", null);
        Analyzer analyzer = analyzerName != null ? ctx.analyzers().get(analyzerName) : ctx.analyzers().defaultAnalyzer();
        boolean norms = getBool(node, "norms", true);
        SearchAsYouTypeFieldMapper mapper = new SearchAsYouTypeFieldMapper(name, fullPath, node, analyzer, norms);
        for (String suffix : SHINGLE_SUFFIXES) {
            String subFullPath = fullPath + suffix;
            mapper.multiFields.put(suffix, new ShingleSubFieldMapper(suffix, subFullPath, new JsonObject(), analyzer, norms));
        }
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
        context.addIndexableField(IndexableField.indexedText(fullPath, terms, norms));
    }

    private static final class ShingleSubFieldMapper extends FieldMapper {
        private final Analyzer analyzer;
        private final boolean norms;

        ShingleSubFieldMapper(String simpleName, String fullPath, JsonObject node, Analyzer analyzer, boolean norms) {
            super(simpleName, fullPath, node);
            this.analyzer = analyzer;
            this.norms = norms;
        }

        @Override
        public String typeName() {
            return "text";
        }

        @Override
        protected void parseCreateField(ParseContext context, Object value) {
            String text = stringValue(value);
            List<IndexedTerm> terms = TextFieldMapper.tokenize(analyzer, fullPath, text);
            context.addIndexableField(IndexableField.indexedText(fullPath, terms, norms));
        }
    }
}
