package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;

import java.util.List;
import java.util.Map;

public final class EstonianAnalyzerFactory {

    private EstonianAnalyzerFactory() {
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stemExclusion = LangAnalyzerSupport.stemExclusion(settings);
        List<TokenFilterFactory> filters = LangAnalyzerSupport.baseChain(
            TokenFilterFactory.of("lowercase", true, LowerCaseFilter::new), stopwords(), stemExclusion);
        filters.add(TokenFilterFactory.of("estonian_stem", in -> new StemmerFilter(in, stemmer())));
        return LangAnalyzerSupport.composed(filters);
    }

    public static Stemmer stemmer() {
        return new EstonianStemmer();
    }

    public static List<String> stopwords() {
        return StopwordLists.get("_estonian_");
    }
}
