package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.filter.DecimalDigitFilter;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.registry.ComposedAnalyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class HindiAnalyzerFactory {

    private HindiAnalyzerFactory() {
    }

    public static Supplier<Tokenizer> tokenizer() {
        return StandardTokenizer::new;
    }

    public static Stemmer indicNormalizer() {
        return new IndicNormalizer();
    }

    public static Stemmer hindiNormalizer() {
        return new HindiNormalizer();
    }

    public static Stemmer stemmer() {
        return new HindiStemmer();
    }

    public static List<String> stopwords() {
        return StopwordLists.get("_hindi_");
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stop = stopwords();
        List<TokenFilterFactory> filters = List.of(
            TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)),
            TokenFilterFactory.of("decimal_digit", true, in -> new DecimalDigitFilter(in)),
            TokenFilterFactory.of("indic_normalization", true, in -> new IndicNormalizationFilter(in)),
            TokenFilterFactory.of("hindi_normalization", true, in -> new HindiNormalizationFilter(in)),
            TokenFilterFactory.of("hindi_stop", in -> new StopFilter(in, stop, false)),
            TokenFilterFactory.of("hindi_stemmer", in -> new StemmerFilter(in, stemmer())));
        return new ComposedAnalyzer(List.of(), tf(tokenizer()), filters, 0);
    }

    private static TokenizerFactory tf(Supplier<Tokenizer> supplier) {
        return new TokenizerFactory() {
            @Override
            public String name() {
                return "standard";
            }

            @Override
            public Tokenizer create() {
                return supplier.get();
            }
        };
    }
}
