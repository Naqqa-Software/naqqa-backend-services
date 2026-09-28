package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.filter.KeywordMarkerFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.registry.ComposedAnalyzer;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LangAnalyzerSupport {

    private LangAnalyzerSupport() {
    }

    static List<String> stemExclusion(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return List.of();
        }
        List<String> list = AnalysisSettings.of(settings).getList("stem_exclusion", true);
        return list == null ? List.of() : list;
    }

    static List<TokenFilterFactory> baseChain(TokenFilterFactory lowerCase, List<String> stopwords, List<String> stemExclusion) {
        List<TokenFilterFactory> filters = new ArrayList<>();
        filters.add(lowerCase);
        filters.add(TokenFilterFactory.of("stop", in -> new StopFilter(in, stopwords, false)));
        if (stemExclusion != null && !stemExclusion.isEmpty()) {
            Set<String> keywords = new HashSet<>(stemExclusion);
            filters.add(TokenFilterFactory.of("keyword_marker", in -> new KeywordMarkerFilter(in, keywords)));
        }
        return filters;
    }

    static TokenizerFactory standardTokenizerFactory() {
        return new TokenizerFactory() {
            @Override
            public String name() {
                return "standard";
            }

            @Override
            public Tokenizer create() {
                return new StandardTokenizer();
            }
        };
    }

    static Analyzer composed(List<TokenFilterFactory> filters) {
        return new ComposedAnalyzer(List.of(), standardTokenizerFactory(), filters, 0);
    }
}
