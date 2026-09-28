package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.CharFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.filter.DecimalDigitFilter;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.registry.ComposedAnalyzer;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class PersianAnalyzerFactory {

    private PersianAnalyzerFactory() {
    }

    public static Supplier<Tokenizer> tokenizer() {
        return StandardTokenizer::new;
    }

    public static Stemmer arabicNormalizer() {
        return new ArabicNormalizer();
    }

    public static Stemmer persianNormalizer() {
        return new PersianNormalizer();
    }

    public static List<String> stopwords() {
        return StopwordLists.get("_persian_");
    }

    public static Analyzer create(Map<String, Object> settings) {
        List<String> stop = stopwords();
        List<CharFilterFactory> charFilters = List.of(cf());
        List<TokenFilterFactory> filters = List.of(
            TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)),
            TokenFilterFactory.of("decimal_digit", true, in -> new DecimalDigitFilter(in)),
            TokenFilterFactory.of("arabic_normalization", true, in -> new ArabicNormalizationFilter(in)),
            TokenFilterFactory.of("persian_normalization", true, in -> new PersianNormalizationFilter(in)),
            TokenFilterFactory.of("persian_stop", in -> new StopFilter(in, stop, false)));
        return new ComposedAnalyzer(charFilters, tf(tokenizer()), filters, 0);
    }

    private static CharFilterFactory cf() {
        return new CharFilterFactory() {
            @Override
            public String name() {
                return "persian_charfilter";
            }

            @Override
            public CharFilter create() {
                return new PersianCharFilter();
            }
        };
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
