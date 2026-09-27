package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.AnalysisContext;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.CharFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.filter.LowerCaseFilter;
import com.naqqa.elasticsearch.analysis.filter.StopFilter;
import com.naqqa.elasticsearch.analysis.tokenizer.KeywordTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.LowerCaseTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.PatternTokenizer;
import com.naqqa.elasticsearch.analysis.tokenizer.StandardTokenizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class AnalysisRegistry {

    private final AnalysisContext context;

    public AnalysisRegistry() {
        this(AnalysisContext.DEFAULT);
    }

    public AnalysisRegistry(AnalysisContext context) {
        this.context = context;
    }

    public IndexAnalyzers build(Map<String, Object> rawSettings) {
        return new Builder(rawSettings).build();
    }

    private final class Builder {
        private final AnalysisSettings charFilterDefs;
        private final AnalysisSettings tokenizerDefs;
        private final AnalysisSettings filterDefs;
        private final AnalysisSettings analyzerDefs;
        private final AnalysisSettings normalizerDefs;
        private final Map<String, CharFilterFactory> charFilterCache = new HashMap<>();
        private final Map<String, TokenizerFactory> tokenizerCache = new HashMap<>();
        private final Map<String, TokenFilterFactory> filterCache = new HashMap<>();
        private final Set<String> filterInProgress = new HashSet<>();

        Builder(Map<String, Object> rawSettings) {
            AnalysisSettings root = AnalysisSettings.of(rawSettings);
            AnalysisSettings analysis = root.getAsSettings("index.analysis");
            if (analysis.isEmpty()) {
                analysis = root.getAsSettings("analysis");
            }
            if (analysis.isEmpty() && (root.has("analyzer") || root.has("tokenizer") || root.has("filter") || root.has("char_filter"))) {
                analysis = root;
            }
            this.charFilterDefs = analysis.getAsSettings("char_filter");
            this.tokenizerDefs = analysis.getAsSettings("tokenizer");
            this.filterDefs = analysis.getAsSettings("filter");
            this.analyzerDefs = analysis.getAsSettings("analyzer");
            this.normalizerDefs = analysis.getAsSettings("normalizer");
        }

        IndexAnalyzers build() {
            Map<String, Analyzer> analyzers = new LinkedHashMap<>();
            for (String name : analyzerDefs.keys()) {
                analyzers.put(name, buildAnalyzer(name, analyzerDefs.getAsSettings(name)));
            }
            for (String name : BuiltinAnalyzers.names()) {
                analyzers.putIfAbsent(name, BuiltinAnalyzers.get(name));
            }
            Map<String, Analyzer> normalizers = new LinkedHashMap<>();
            normalizers.put("lowercase", lowercaseNormalizer());
            for (String name : normalizerDefs.keys()) {
                normalizers.put(name, buildNormalizer(name, normalizerDefs.getAsSettings(name)));
            }
            String defaultName = analyzerDefs.has("default") ? "default" : "standard";
            String defaultSearchName = analyzerDefs.has("default_search") ? "default_search" : defaultName;
            return new IndexAnalyzers(analyzers, normalizers, defaultName, defaultSearchName);
        }

        private Analyzer lowercaseNormalizer() {
            List<TokenFilterFactory> filters = List.of(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)));
            return new ComposedAnalyzer(List.of(), keywordTokenizerFactory(), filters, 0, true);
        }

        private Analyzer buildAnalyzer(String name, AnalysisSettings def) {
            String type = def.getString("type", def.has("tokenizer") ? "custom" : null);
            if (type == null || type.equals("custom")) {
                if (!def.has("tokenizer")) {
                    throw new IllegalArgumentException("analyzer [" + name + "] must specify either an analyzer type, or a tokenizer");
                }
                List<CharFilterFactory> cf = resolveList(def.getList("char_filter"), this::resolveCharFilter);
                TokenizerFactory tok = resolveTokenizer(def.getString("tokenizer"));
                List<TokenFilterFactory> filters = resolveList(def.getList("filter"), this::resolveFilter);
                int gap = def.getInt("position_increment_gap", 100);
                return new ComposedAnalyzer(cf, tok, filters, gap);
            }
            switch (type) {
                case "standard": {
                    int maxLen = def.getInt("max_token_length", StandardTokenizer.DEFAULT_MAX_TOKEN_LENGTH);
                    List<TokenFilterFactory> filters = List.of(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)));
                    return new ComposedAnalyzer(List.of(), simpleTokenizerFactory(() -> new StandardTokenizer(maxLen)), filters, 0);
                }
                case "stop": {
                    List<String> stopwords = TokenFilterFactories.resolveStopwords(def, context, "_english_");
                    List<TokenFilterFactory> filters = List.of(TokenFilterFactory.of("stop", in -> new StopFilter(in, stopwords, false)));
                    return new ComposedAnalyzer(List.of(), simpleTokenizerFactory(LowerCaseTokenizer::new), filters, 0);
                }
                case "pattern": {
                    Pattern pattern = Pattern.compile(def.getString("pattern", "\\W+"));
                    boolean lower = def.getBoolean("lowercase", true);
                    List<TokenFilterFactory> filters = lower
                        ? List.of(TokenFilterFactory.of("lowercase", true, in -> new LowerCaseFilter(in)))
                        : List.of();
                    return new ComposedAnalyzer(List.of(), simpleTokenizerFactory(() -> new PatternTokenizer(pattern, -1)), filters, 0);
                }
                default:
                    if (BuiltinAnalyzers.has(type)) {
                        return BuiltinAnalyzers.get(type);
                    }
                    throw new IllegalArgumentException("Unknown analyzer type [" + type + "] for [" + name + "]");
            }
        }

        private Analyzer buildNormalizer(String name, AnalysisSettings def) {
            List<CharFilterFactory> cf = resolveList(def.getList("char_filter"), this::resolveCharFilter);
            List<TokenFilterFactory> filters = resolveList(def.getList("filter"), this::resolveFilter);
            return new ComposedAnalyzer(cf, keywordTokenizerFactory(), filters, 0, true);
        }

        private <T> List<T> resolveList(List<String> names, java.util.function.Function<String, T> resolver) {
            if (names == null) {
                return List.of();
            }
            List<T> out = new ArrayList<>();
            for (String n : names) {
                out.add(resolver.apply(n));
            }
            return out;
        }

        private CharFilterFactory resolveCharFilter(String name) {
            CharFilterFactory cached = charFilterCache.get(name);
            if (cached != null) {
                return cached;
            }
            CharFilterFactory factory;
            if (charFilterDefs.has(name)) {
                AnalysisSettings def = charFilterDefs.getAsSettings(name);
                factory = CharFilterFactories.create(name, def.getString("type"), def, context);
            } else {
                factory = CharFilterFactories.create(name, name, AnalysisSettings.EMPTY, context);
            }
            charFilterCache.put(name, factory);
            return factory;
        }

        private TokenizerFactory resolveTokenizer(String name) {
            if (name == null) {
                throw new IllegalArgumentException("tokenizer must be specified");
            }
            TokenizerFactory cached = tokenizerCache.get(name);
            if (cached != null) {
                return cached;
            }
            TokenizerFactory factory;
            if (tokenizerDefs.has(name)) {
                AnalysisSettings def = tokenizerDefs.getAsSettings(name);
                factory = TokenizerFactories.create(name, def.getString("type"), def, context);
            } else {
                factory = TokenizerFactories.create(name, name, AnalysisSettings.EMPTY, context);
            }
            tokenizerCache.put(name, factory);
            return factory;
        }

        private TokenFilterFactory resolveFilter(String name) {
            TokenFilterFactory cached = filterCache.get(name);
            if (cached != null) {
                return cached;
            }
            if (!filterInProgress.add(name)) {
                throw new IllegalArgumentException("Cyclic reference resolving token filter [" + name + "]");
            }
            try {
                TokenFilterFactory factory;
                if (filterDefs.has(name)) {
                    AnalysisSettings def = filterDefs.getAsSettings(name);
                    factory = TokenFilterFactories.create(name, def.getString("type"), def, context, this::resolveFilter);
                } else {
                    factory = TokenFilterFactories.create(name, name, AnalysisSettings.EMPTY, context, this::resolveFilter);
                }
                filterCache.put(name, factory);
                return factory;
            } finally {
                filterInProgress.remove(name);
            }
        }

        private TokenizerFactory simpleTokenizerFactory(java.util.function.Supplier<Tokenizer> supplier) {
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

        private TokenizerFactory keywordTokenizerFactory() {
            return simpleTokenizerFactory(KeywordTokenizer::new);
        }
    }
}
