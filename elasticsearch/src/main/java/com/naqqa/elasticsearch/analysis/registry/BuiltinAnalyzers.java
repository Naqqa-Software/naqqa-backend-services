package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.CharFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.filter.ElisionFilter;
import com.naqqa.elasticsearch.analysis.filter.CJKBigramFilter;
import com.naqqa.elasticsearch.analysis.filter.CJKWidthFilter;
import com.naqqa.elasticsearch.analysis.filter.FingerprintFilter;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.StemmerFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.stem.Stemmer;
import com.naqqa.elasticsearch.analysis.stem.Stemmers;
import com.naqqa.elasticsearch.analysis.stem.english.EnglishPossessiveStemmer;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;
import com.naqqa.elasticsearch.analysis.tokenizer.KeywordTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.LowerCaseTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.PatternTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.WhitespaceTokenizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class BuiltinAnalyzers {

    private BuiltinAnalyzers() {
    }

    private static final Map<String, Supplier<Analyzer>> BUILTIN = build();

    public static boolean has(String name) {
        return BUILTIN.containsKey(name);
    }

    public static java.util.Set<String> names() {
        return BUILTIN.keySet();
    }

    public static Analyzer get(String name) {
        Supplier<Analyzer> s = BUILTIN.get(name);
        return s == null ? null : s.get();
    }

    private static Map<String, Supplier<Analyzer>> build() {
        Map<String, Supplier<Analyzer>> m = new LinkedHashMap<>();
        m.put("standard", () -> composed(StandardTokenizer::new, List.of(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)))));
        m.put("simple", () -> composed(LowerCaseTokenizer::new, List.of()));
        m.put("whitespace", () -> composed(WhitespaceTokenizer::new, List.of()));
        m.put("stop", () -> composed(LowerCaseTokenizer::new,
            List.of(TokenFilterFactory.of("stop", in -> new StopFilter(in, StopwordLists.get("_english_"), false)))));
        m.put("keyword", () -> composed(KeywordTokenizer::new, List.of()));
        m.put("pattern", () -> composed(() -> new PatternTokenizer(Pattern.compile("\\W+"), -1),
            List.of(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)))));
        m.put("fingerprint", () -> composed(StandardTokenizer::new, List.of(
            TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)),
            TokenFilterFactory.of("fingerprint", in -> new FingerprintFilter(in, ' ', 255)))));
        m.put("english", () -> language("_english_", () -> Stemmers.create("english"), EnglishPossessiveStemmer::new, null));
        m.put("french", () -> language("_french_", () -> Stemmers.create("light_french"), null, frenchArticles()));
        m.put("german", () -> language("_german_", () -> Stemmers.create("light_german"), null, null));
        m.put("spanish", () -> language("_spanish_", () -> Stemmers.create("light_spanish"), null, null));
        m.put("italian", () -> language("_italian_", () -> Stemmers.create("light_italian"), null, italianArticles()));
        m.put("portuguese", () -> language("_portuguese_", () -> Stemmers.create("light_portuguese"), null, null));
        m.put("dutch", () -> language("_dutch_", () -> Stemmers.create("dutch"), null, null));
        m.put("romanian", () -> language("_romanian_", () -> Stemmers.create("romanian"), null, null));
        m.put("russian", () -> language("_russian_", () -> Stemmers.create("russian"), null, null));
        m.put("swedish", () -> language("_swedish_", () -> Stemmers.create("swedish"), null, null));
        m.put("danish", () -> language("_danish_", () -> Stemmers.create("danish"), null, null));
        m.put("norwegian", () -> language("_norwegian_", () -> Stemmers.create("norwegian"), null, null));
        m.put("finnish", () -> language("_finnish_", () -> Stemmers.create("finnish"), null, null));
        m.put("cjk", BuiltinAnalyzers::cjk);
        m.put("brazilian", () -> com.naqqa.elasticsearch.analysis.lang.b.BrazilianAnalyzerFactory.create(Map.of()));
        m.put("bulgarian", () -> com.naqqa.elasticsearch.analysis.lang.b.BulgarianAnalyzerFactory.create(Map.of()));
        m.put("czech", () -> com.naqqa.elasticsearch.analysis.lang.b.CzechAnalyzerFactory.create(Map.of()));
        m.put("estonian", () -> com.naqqa.elasticsearch.analysis.lang.b.EstonianAnalyzerFactory.create(Map.of()));
        m.put("galician", () -> com.naqqa.elasticsearch.analysis.lang.b.GalicianAnalyzerFactory.create(Map.of()));
        m.put("greek", () -> com.naqqa.elasticsearch.analysis.lang.b.GreekAnalyzerFactory.create(Map.of()));
        m.put("latvian", () -> com.naqqa.elasticsearch.analysis.lang.b.LatvianAnalyzerFactory.create(Map.of()));
        m.put("lithuanian", () -> com.naqqa.elasticsearch.analysis.lang.b.LithuanianAnalyzerFactory.create(Map.of()));
        m.put("serbian", () -> com.naqqa.elasticsearch.analysis.lang.b.SerbianAnalyzerFactory.create(Map.of()));
        m.put("arabic", () -> com.naqqa.elasticsearch.analysis.lang.a.ArabicAnalyzerFactory.create(Map.of()));
        m.put("armenian", () -> com.naqqa.elasticsearch.analysis.lang.a.ArmenianAnalyzerFactory.create(Map.of()));
        m.put("basque", () -> com.naqqa.elasticsearch.analysis.lang.a.BasqueAnalyzerFactory.create(Map.of()));
        m.put("catalan", () -> com.naqqa.elasticsearch.analysis.lang.a.CatalanAnalyzerFactory.create(Map.of()));
        m.put("hungarian", () -> com.naqqa.elasticsearch.analysis.lang.a.HungarianAnalyzerFactory.create(Map.of()));
        m.put("indonesian", () -> com.naqqa.elasticsearch.analysis.lang.a.IndonesianAnalyzerFactory.create(Map.of()));
        m.put("irish", () -> com.naqqa.elasticsearch.analysis.lang.a.IrishAnalyzerFactory.create(Map.of()));
        m.put("turkish", () -> com.naqqa.elasticsearch.analysis.lang.a.TurkishAnalyzerFactory.create(Map.of()));
        m.put("hindi", () -> com.naqqa.elasticsearch.analysis.lang.c.HindiAnalyzerFactory.create(Map.of()));
        m.put("bengali", () -> com.naqqa.elasticsearch.analysis.lang.c.BengaliAnalyzerFactory.create(Map.of()));
        m.put("persian", () -> com.naqqa.elasticsearch.analysis.lang.c.PersianAnalyzerFactory.create(Map.of()));
        m.put("sorani", () -> com.naqqa.elasticsearch.analysis.lang.c.SoraniAnalyzerFactory.create(Map.of()));
        m.put("thai", () -> com.naqqa.elasticsearch.analysis.lang.c.ThaiAnalyzerFactory.create(Map.of()));
        return m;
    }

    private static Analyzer composed(Supplier<Tokenizer> tokenizer, List<TokenFilterFactory> filters) {
        return new ComposedAnalyzer(List.of(), tf(tokenizer), filters, 0);
    }

    private static Analyzer language(String stopwordsKey, Supplier<Stemmer> stemmer, Supplier<Stemmer> preStemmer, List<String> elisionArticles) {
        stemmer.get();
        List<TokenFilterFactory> filters = new java.util.ArrayList<>();
        if (elisionArticles != null) {
            java.util.Set<String> articles = new java.util.HashSet<>(elisionArticles);
            filters.add(TokenFilterFactory.of("elision", in -> new ElisionFilter(in, articles)));
        }
        if (preStemmer != null) {
            filters.add(TokenFilterFactory.of("possessive", in -> new StemmerFilter(in, preStemmer.get())));
        }
        filters.add(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)));
        filters.add(TokenFilterFactory.of("stop", in -> new StopFilter(in, StopwordLists.get(stopwordsKey), false)));
        filters.add(TokenFilterFactory.of("stemmer", in -> new StemmerFilter(in, stemmer.get())));
        return new ComposedAnalyzer(List.of(), tf(StandardTokenizer::new), filters, 0);
    }

    private static Analyzer cjk() {
        List<TokenFilterFactory> filters = List.of(
            TokenFilterFactory.of("cjk_width", true, in -> new CJKWidthFilter(in)),
            TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)),
            TokenFilterFactory.of("cjk_bigram", in -> new CJKBigramFilter(in, true, true, true, true, false)),
            TokenFilterFactory.of("stop", in -> new StopFilter(in, StopwordLists.get("_english_"), false)));
        return new ComposedAnalyzer(List.of(), tf(StandardTokenizer::new), filters, 0);
    }

    private static List<String> frenchArticles() {
        return List.of("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu");
    }

    private static List<String> italianArticles() {
        return List.of("c", "l", "all", "dall", "dell", "nell", "sull", "coll", "pell", "gl", "agl", "dagl", "quest", "un", "m", "t", "s", "v", "d");
    }

    private static TokenizerFactory tf(Supplier<Tokenizer> supplier) {
        return new TokenizerFactory() {
            @Override
            public String name() {
                return "builtin";
            }

            @Override
            public Tokenizer create() {
                return supplier.get();
            }
        };
    }
}
