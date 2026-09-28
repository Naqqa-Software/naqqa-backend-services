package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.filter.ElisionFilter;
import com.naqqa.elasticsearch.analysis.filter.KeywordMarkerFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IrishAnalyzerFactory {

    public static final String STOPWORDS_KEY = "_irish_";

    private static final Set<String> DEFAULT_ARTICLES = Set.of("d", "m", "b");
    private static final List<String> HYPHENATIONS = List.of("h", "n", "t");

    private IrishAnalyzerFactory() {
    }

    public static Stemmer stemmer() {
        return new IrishSnowballStemmer();
    }

    public static List<String> stopwords(Map<String, Object> settings) {
        return LangAnalyzerSupport.stopwords(settings, STOPWORDS_KEY);
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stopwords = stopwords(settings);
        Set<String> exclusion = LangAnalyzerSupport.stemExclusion(settings);
        Set<String> articles = new HashSet<>(DEFAULT_ARTICLES);
        List<TokenFilterFactory> filters = new ArrayList<>();
        filters.add(TokenFilterFactory.of("hyphenations_stop", in -> new StopFilter(in, HYPHENATIONS, true)));
        filters.add(TokenFilterFactory.of("elision", true, in -> new ElisionFilter(in, articles)));
        filters.add(TokenFilterFactory.of("irish_lowercase", true, IrishLowerCaseFilter::new));
        filters.add(TokenFilterFactory.of("stop", in -> new StopFilter(in, stopwords, false)));
        if (!exclusion.isEmpty()) {
            filters.add(TokenFilterFactory.of("keyword_marker", in -> new KeywordMarkerFilter(in, exclusion)));
        }
        filters.add(TokenFilterFactory.of("stemmer", in -> new StemmerFilter(in, stemmer())));
        return LangAnalyzerSupport.composed(StandardTokenizer::new, filters);
    }
}
