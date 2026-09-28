package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.filter.KeywordMarkerFilter;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IndonesianAnalyzerFactory {

    public static final String STOPWORDS_KEY = "_indonesian_";

    private IndonesianAnalyzerFactory() {
    }

    public static Stemmer stemmer() {
        return new IndonesianStemmer(true);
    }

    public static Stemmer stemmer(boolean stemDerivational) {
        return new IndonesianStemmer(stemDerivational);
    }

    public static List<String> stopwords(Map<String, Object> settings) {
        return LangAnalyzerSupport.stopwords(settings, STOPWORDS_KEY);
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stopwords = stopwords(settings);
        Set<String> exclusion = LangAnalyzerSupport.stemExclusion(settings);
        boolean stemDerivational = AnalysisSettings.of(settings).getBoolean("stem_derivational", true);
        List<TokenFilterFactory> filters = new ArrayList<>();
        filters.add(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)));
        filters.add(TokenFilterFactory.of("stop", in -> new StopFilter(in, stopwords, false)));
        if (!exclusion.isEmpty()) {
            filters.add(TokenFilterFactory.of("keyword_marker", in -> new KeywordMarkerFilter(in, exclusion)));
        }
        filters.add(TokenFilterFactory.of("stemmer", in -> new StemmerFilter(in, stemmer(stemDerivational))));
        return LangAnalyzerSupport.composed(StandardTokenizer::new, filters);
    }
}
