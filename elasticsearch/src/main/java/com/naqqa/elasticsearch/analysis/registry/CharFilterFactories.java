package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.AnalysisContext;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.CharFilterFactory;
import com.naqqa.elasticsearch.analysis.charfilter.MappingCharFilter;
import com.naqqa.elasticsearch.analysis.charfilter.PatternReplaceCharFilter;
import com.naqqa.elasticsearch.analysis.charfilter.html.HtmlStripCharFilter;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class CharFilterFactories {

    private CharFilterFactories() {
    }

    public static CharFilterFactory create(String name, String type, AnalysisSettings settings, AnalysisContext context) {
        switch (type) {
            case "html_strip": {
                List<String> escaped = context.getWordList(settings, "escaped_tags");
                Set<String> tags = escaped == null ? null : new HashSet<>(escaped);
                return of(name, () -> new HtmlStripCharFilter(tags));
            }
            case "mapping": {
                List<String> rules = context.getWordList(settings, "mappings");
                Map<String, String> parsed = MappingCharFilter.parseRules(rules);
                return of(name, () -> new MappingCharFilter(parsed));
            }
            case "pattern_replace": {
                Pattern pattern = Pattern.compile(settings.getString("pattern"));
                String replacement = settings.getString("replacement", "");
                return of(name, () -> new PatternReplaceCharFilter(pattern, replacement));
            }
            default:
                throw new IllegalArgumentException("Unknown char_filter type [" + type + "]");
        }
    }

    private static CharFilterFactory of(String name, Supplier<CharFilter> supplier) {
        return new CharFilterFactory() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CharFilter create() {
                return supplier.get();
            }
        };
    }
}
