package com.naqqa.elasticsearch.analysis.lang.a;

import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;
import com.naqqa.elasticsearch.analysis.registry.ComposedAnalyzer;
import com.naqqa.elasticsearch.analysis.stop.StopwordLists;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class LangAnalyzerSupport {

    private LangAnalyzerSupport() {
    }

    static List<String> stopwords(Map<String, Object> settings, String defaultKey) {
        AnalysisSettings s = AnalysisSettings.of(settings);
        Object raw = s.get("stopwords");
        if (raw == null) {
            return StopwordLists.get(defaultKey);
        }
        if (raw instanceof String str && StopwordLists.isKnown(str)) {
            return StopwordLists.get(str);
        }
        List<String> list = s.getList("stopwords");
        List<String> out = new ArrayList<>();
        for (String w : list) {
            if (StopwordLists.isKnown(w)) {
                out.addAll(StopwordLists.get(w));
            } else {
                out.add(w);
            }
        }
        return out;
    }

    static Set<String> stemExclusion(Map<String, Object> settings) {
        AnalysisSettings s = AnalysisSettings.of(settings);
        List<String> list = s.getList("stem_exclusion");
        return list == null ? Set.of() : new HashSet<>(list);
    }

    static TokenizerFactory tf(Supplier<Tokenizer> supplier) {
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

    static Analyzer composed(Supplier<Tokenizer> tokenizer, List<TokenFilterFactory> filters) {
        return new ComposedAnalyzer(List.of(), tf(tokenizer), filters, 0);
    }
}
