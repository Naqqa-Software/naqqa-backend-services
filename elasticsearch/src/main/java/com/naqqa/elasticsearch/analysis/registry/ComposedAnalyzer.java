package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.CharFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.TokenStreamComponents;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;

import java.util.List;

public final class ComposedAnalyzer extends Analyzer {

    private final List<CharFilterFactory> charFilters;
    private final TokenizerFactory tokenizer;
    private final List<TokenFilterFactory> tokenFilters;
    private final int positionIncrementGap;
    private final boolean normalize;

    public ComposedAnalyzer(List<CharFilterFactory> charFilters, TokenizerFactory tokenizer, List<TokenFilterFactory> tokenFilters,
                             int positionIncrementGap) {
        this(charFilters, tokenizer, tokenFilters, positionIncrementGap, false);
    }

    public ComposedAnalyzer(List<CharFilterFactory> charFilters, TokenizerFactory tokenizer, List<TokenFilterFactory> tokenFilters,
                             int positionIncrementGap, boolean normalize) {
        this.charFilters = charFilters;
        this.tokenizer = tokenizer;
        this.tokenFilters = tokenFilters;
        this.positionIncrementGap = positionIncrementGap;
        this.normalize = normalize;
    }

    public List<CharFilterFactory> charFilterFactories() {
        return charFilters;
    }

    public TokenizerFactory tokenizerFactory() {
        return tokenizer;
    }

    public List<TokenFilterFactory> tokenFilterFactories() {
        return tokenFilters;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer source = tokenizer.create();
        TokenStream sink = source;
        for (TokenFilterFactory f : tokenFilters) {
            sink = normalize ? f.normalize(sink) : f.create(sink);
        }
        CharFilter[] cf = new CharFilter[charFilters.size()];
        for (int i = 0; i < cf.length; i++) {
            cf[i] = charFilters.get(i).create();
        }
        return new TokenStreamComponents(cf, source, sink);
    }

    @Override
    protected TokenStreamComponents createNormalizationComponents(String fieldName) {
        return createComponents(fieldName);
    }

    @Override
    public int getPositionIncrementGap(String fieldName) {
        return positionIncrementGap;
    }
}
