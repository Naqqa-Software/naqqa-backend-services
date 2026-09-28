package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;

import java.util.List;
import java.util.Map;

public final class GreekAnalyzerFactory {

    private GreekAnalyzerFactory() {
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stemExclusion = LangAnalyzerSupport.stemExclusion(settings);
        List<TokenFilterFactory> filters = LangAnalyzerSupport.baseChain(
            TokenFilterFactory.of("greek_lowercase", true, GreekLowerCaseFilter::new), stopwords(), stemExclusion);
        filters.add(TokenFilterFactory.of("greek_stem", in -> new StemmerFilter(in, stemmer())));
        return LangAnalyzerSupport.composed(filters);
    }

    public static Stemmer stemmer() {
        return new GreekStemmer();
    }

    public static List<String> stopwords() {
        return StopwordLists.get("_greek_");
    }
}
