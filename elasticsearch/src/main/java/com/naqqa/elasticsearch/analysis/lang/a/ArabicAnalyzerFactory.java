package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.filter.DecimalDigitFilter;
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

public final class ArabicAnalyzerFactory {

    public static final String STOPWORDS_KEY = "_arabic_";

    private ArabicAnalyzerFactory() {
    }

    public static Stemmer normalizer() {
        return new ArabicNormalizerStemmer();
    }

    public static Stemmer stemmer() {
        return new ArabicLightStemmer();
    }

    public static List<String> stopwords(Map<String, Object> settings) {
        return LangAnalyzerSupport.stopwords(settings, STOPWORDS_KEY);
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stopwords = stopwords(settings);
        Set<String> exclusion = LangAnalyzerSupport.stemExclusion(settings);
        List<TokenFilterFactory> filters = new ArrayList<>();
        filters.add(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)));
        filters.add(TokenFilterFactory.of("decimal_digit", true, DecimalDigitFilter::new));
        filters.add(TokenFilterFactory.of("stop", in -> new StopFilter(in, stopwords, false)));
        filters.add(TokenFilterFactory.of("arabic_normalization", true, in -> new StemmerFilter(in, normalizer())));
        if (!exclusion.isEmpty()) {
            filters.add(TokenFilterFactory.of("keyword_marker", in -> new KeywordMarkerFilter(in, exclusion)));
        }
        filters.add(TokenFilterFactory.of("arabic_stem", in -> new StemmerFilter(in, stemmer())));
        return LangAnalyzerSupport.composed(StandardTokenizer::new, filters);
    }
}
