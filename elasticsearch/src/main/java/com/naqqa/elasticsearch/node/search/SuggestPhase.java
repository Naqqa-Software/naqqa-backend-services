package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.search.suggest.completion.CompletionSegmentIndex;
import com.naqqa.elasticsearch.search.suggest.completion.FuzzyOptions;
import com.naqqa.elasticsearch.search.suggest.phrase.NGramLanguageModel;
import com.naqqa.elasticsearch.search.suggest.phrase.PhraseCandidate;
import com.naqqa.elasticsearch.search.suggest.phrase.PhraseSuggester;
import com.naqqa.elasticsearch.search.suggest.term.DistanceMetric;
import com.naqqa.elasticsearch.search.suggest.term.SuggestMode;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggestOptions;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggester;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

final class SuggestPhase {

    private record TokenSpan(String term, int offset, int length) {
    }

    private SuggestPhase() {
    }

    static Map<String, Object> execute(List<ShardTarget> shards, Map<String, Object> spec) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        String globalText = spec.get("text") == null ? null : String.valueOf(spec.get("text"));
        for (Map.Entry<String, Object> e : spec.entrySet()) {
            if ("text".equals(e.getKey())) {
                continue;
            }
            Map<String, Object> entry = SettingsMaps.asMap(e.getValue());
            if (entry == null) {
                throw new RestApiException(400, "suggestion [" + e.getKey() + "] must be an object");
            }
            String text = entry.get("text") != null ? String.valueOf(entry.get("text"))
                : entry.get("prefix") != null ? String.valueOf(entry.get("prefix")) : globalText;
            if (entry.get("term") != null) {
                out.put(e.getKey(), term(shards, requireText(text, e.getKey()), SettingsMaps.asMap(entry.get("term"))));
            } else if (entry.get("phrase") != null) {
                out.put(e.getKey(), phrase(shards, requireText(text, e.getKey()), SettingsMaps.asMap(entry.get("phrase"))));
            } else if (entry.get("completion") != null) {
                out.put(e.getKey(), completion(shards, requireText(text, e.getKey()), SettingsMaps.asMap(entry.get("completion"))));
            } else {
                throw new RestApiException(400, "suggestion [" + e.getKey() + "] must define one of [term], [phrase], [completion]");
            }
        }
        return out;
    }

    private static String requireText(String text, String name) {
        if (text == null) {
            throw new RestApiException(400, "The required text option is missing for suggestion [" + name + "]");
        }
        return text;
    }

    private static String requireField(Map<String, Object> opts) {
        if (opts == null || opts.get("field") == null) {
            throw new RestApiException(400, "the required field option [field] is missing");
        }
        return String.valueOf(opts.get("field"));
    }

    private static Map<String, Integer> termFrequencies(List<ShardTarget> shards, String field) throws IOException {
        Map<String, Integer> freq = new TreeMap<>();
        for (ShardTarget shard : shards) {
            for (SegmentReader segment : shard.segments) {
                TermsEnum te = segment.terms(field);
                if (te == null) {
                    continue;
                }
                byte[] t;
                while ((t = te.next()) != null) {
                    freq.merge(new String(t, StandardCharsets.UTF_8), te.docFreq(), Integer::sum);
                }
            }
        }
        return freq;
    }

    private static Iterable<String> expand(Map<String, Integer> freq) {
        return () -> new Iterator<>() {
            private final Iterator<Map.Entry<String, Integer>> entries = freq.entrySet().iterator();
            private String current;
            private int remaining;

            @Override
            public boolean hasNext() {
                while (remaining == 0 && entries.hasNext()) {
                    Map.Entry<String, Integer> e = entries.next();
                    current = e.getKey();
                    remaining = e.getValue();
                }
                return remaining > 0;
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                remaining--;
                return current;
            }
        };
    }

    private static Analyzer analyzer(List<ShardTarget> shards, String field, boolean search) {
        for (ShardTarget shard : shards) {
            Map<String, Object> def = HighlightPhase.mappingDefinition(shard, field);
            if (def != null) {
                return search ? HighlightPhase.searchAnalyzerFor(shard, def) : HighlightPhase.analyzerFor(shard, def);
            }
        }
        return shards.isEmpty() ? null : HighlightPhase.searchAnalyzerFor(shards.get(0), null);
    }

    private static List<TokenSpan> tokens(Analyzer analyzer, String field, String text) {
        List<TokenSpan> out = new ArrayList<>();
        if (analyzer == null) {
            int pos = 0;
            for (String part : text.split("\\s+")) {
                int idx = text.indexOf(part, pos);
                if (!part.isEmpty()) {
                    out.add(new TokenSpan(part.toLowerCase(Locale.ROOT), idx, part.length()));
                }
                pos = idx + part.length();
            }
            return out;
        }
        TokenStream ts = analyzer.tokenStream(field, text);
        try {
            ts.reset();
            while (ts.incrementToken()) {
                Token t = ts.token();
                out.add(new TokenSpan(t.term(), t.startOffset(), t.endOffset() - t.startOffset()));
            }
            ts.end();
        } finally {
            ts.close();
        }
        return out;
    }

    private static TermSuggestOptions termOptions(Map<String, Object> opts, SuggestMode defaultMode) {
        TermSuggestOptions options = new TermSuggestOptions();
        options.size(SearchEngine.intValue(opts.get("size"), 5));
        String mode = opts.get("suggest_mode") == null ? null : String.valueOf(opts.get("suggest_mode")).toUpperCase(Locale.ROOT);
        options.suggestMode(mode == null ? defaultMode : switch (mode) {
            case "POPULAR" -> SuggestMode.POPULAR;
            case "ALWAYS" -> SuggestMode.ALWAYS;
            case "MISSING" -> SuggestMode.MISSING;
            default -> throw new RestApiException(400, "illegal suggest_mode [" + opts.get("suggest_mode") + "]");
        });
        int maxEdits = SearchEngine.intValue(opts.get("max_edits"), 2);
        if (maxEdits < 1 || maxEdits > 2) {
            throw new RestApiException(400, "Illegal max_edits value " + maxEdits);
        }
        options.maxEdits(maxEdits);
        options.prefixLength(SearchEngine.intValue(opts.get("prefix_length"), 1));
        options.minWordLength(SearchEngine.intValue(opts.get("min_word_length"), 4));
        String distance = opts.get("string_distance") == null ? "internal" : String.valueOf(opts.get("string_distance"));
        options.distanceMetric(switch (distance) {
            case "levenshtein" -> DistanceMetric.LEVENSHTEIN;
            case "jaro_winkler" -> DistanceMetric.JARO_WINKLER;
            default -> DistanceMetric.INTERNAL;
        });
        return options;
    }

    private static double similarity(String a, String b, int edits) {
        int len = Math.max(1, Math.min(a.length(), b.length()));
        return Math.max(0.0, 1.0 - (double) edits / len);
    }

    private static List<Object> term(List<ShardTarget> shards, String text, Map<String, Object> opts) throws IOException {
        String field = requireField(opts);
        TermSuggestOptions options = termOptions(opts, SuggestMode.MISSING);
        Map<String, Integer> freq = termFrequencies(shards, field);
        TermSuggester suggester = new TermSuggester(expand(freq));
        boolean byFrequency = "frequency".equals(opts.get("sort"));
        List<Object> entries = new ArrayList<>();
        Analyzer analyzer = opts.get("analyzer") != null && !shards.isEmpty() && shards.get(0).indexService != null
            && shards.get(0).indexService.mapperService().indexAnalyzers().has(String.valueOf(opts.get("analyzer")))
            ? shards.get(0).indexService.mapperService().indexAnalyzers().get(String.valueOf(opts.get("analyzer")))
            : analyzer(shards, field, true);
        for (TokenSpan token : tokens(analyzer, field, text)) {
            List<Map<String, Object>> optionList = new ArrayList<>();
            for (TermSuggestion s : suggester.suggest(token.term(), options)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("text", s.text());
                o.put("score", similarity(token.term(), s.text(), s.editDistance()));
                o.put("freq", s.frequency());
                optionList.add(o);
            }
            Comparator<Map<String, Object>> cmp = Comparator.comparingDouble(o -> -((Number) o.get("score")).doubleValue());
            Comparator<Map<String, Object>> byFreq = Comparator.comparingInt(o -> -((Number) o.get("freq")).intValue());
            optionList.sort(byFrequency ? byFreq.thenComparing(cmp) : cmp.thenComparing(byFreq));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", text.substring(token.offset(), Math.min(text.length(), token.offset() + token.length())));
            entry.put("offset", token.offset());
            entry.put("length", token.length());
            entry.put("options", optionList);
            entries.add(entry);
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> phrase(List<ShardTarget> shards, String text, Map<String, Object> opts) throws IOException {
        String field = requireField(opts);
        int size = SearchEngine.intValue(opts.get("size"), 5);
        Analyzer analyzer = analyzer(shards, field, false);
        List<List<String>> sentences = new ArrayList<>();
        List<String> corpus = new ArrayList<>();
        for (ShardTarget shard : shards) {
            for (SegmentReader segment : shard.segments) {
                for (int d = 0; d < segment.maxDoc(); d++) {
                    if (!segment.isLive(d)) {
                        continue;
                    }
                    StoredDocCodec.Decoded decoded = segment.storedDocument(d);
                    if (decoded == null || decoded.source() == null) {
                        continue;
                    }
                    for (Object v : FieldValues.fromSource(SearchEngine.sourceOf(decoded.source()), field)) {
                        List<String> words = new ArrayList<>();
                        for (TokenSpan t : tokens(analyzer, field, String.valueOf(v))) {
                            words.add(t.term());
                        }
                        sentences.add(words);
                        corpus.addAll(words);
                    }
                }
            }
        }
        Map<String, Object> generator = null;
        if (opts.get("direct_generator") instanceof List<?> gens && !gens.isEmpty()) {
            generator = SettingsMaps.asMap(gens.get(0));
        }
        TermSuggestOptions termOptions = termOptions(generator == null ? Map.of() : generator, SuggestMode.ALWAYS);
        if (generator == null || generator.get("min_word_length") == null) {
            termOptions.minWordLength(2);
        }
        NGramLanguageModel model = new NGramLanguageModel(sentences, 0.5, 0.7);
        PhraseSuggester suggester = new PhraseSuggester(new TermSuggester(corpus), model);
        List<String> inputTokens = new ArrayList<>();
        for (TokenSpan t : tokens(analyzer, field, text)) {
            inputTokens.add(t.term());
        }
        String normalized = String.join(" ", inputTokens);
        Map<String, Object> highlight = SettingsMaps.asMap(opts.get("highlight"));
        String preTag = highlight == null || highlight.get("pre_tag") == null ? null : String.valueOf(highlight.get("pre_tag"));
        String postTag = highlight == null || highlight.get("post_tag") == null ? null : String.valueOf(highlight.get("post_tag"));
        double inputScore = Math.exp(model.logProbability(inputTokens));
        double confidence = opts.get("confidence") instanceof Number n ? n.doubleValue() : 1.0;
        List<Map<String, Object>> options = new ArrayList<>();
        if (!normalized.isEmpty()) {
            for (PhraseCandidate candidate : suggester.suggest(normalized, size + 1, termOptions, null)) {
                if (candidate.text().equals(normalized)) {
                    continue;
                }
                double score = Math.exp(candidate.score());
                if (confidence > 0 && score < inputScore * confidence) {
                    continue;
                }
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("text", candidate.text());
                if (preTag != null && postTag != null) {
                    List<String> words = candidate.words();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < words.size(); i++) {
                        if (i > 0) {
                            sb.append(' ');
                        }
                        boolean changed = i >= inputTokens.size() || !inputTokens.get(i).equals(words.get(i));
                        sb.append(changed ? preTag + words.get(i) + postTag : words.get(i));
                    }
                    o.put("highlighted", sb.toString());
                }
                o.put("score", score);
                options.add(o);
                if (options.size() >= size) {
                    break;
                }
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("text", text);
        entry.put("offset", 0);
        entry.put("length", text.length());
        entry.put("options", options);
        return List.of(entry);
    }

    private record SegmentMatch(ShardTarget shard, int doc, long weight, String original) {
    }

    private static List<Object> completion(List<ShardTarget> shards, String prefix, Map<String, Object> opts) throws IOException {
        String field = requireField(opts);
        int size = SearchEngine.intValue(opts.get("size"), 5);
        boolean skipDuplicates = Boolean.TRUE.equals(opts.get("skip_duplicates")) || "true".equals(opts.get("skip_duplicates"));
        for (ShardTarget shard : shards) {
            QueryFactory.FieldType type = shard.fieldType(field);
            if (type != null && !"completion".equals(type.type())) {
                throw new RestApiException(400, "Field [" + field + "] is not a completion suggest field");
            }
        }
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        Map<String, Object> fuzzy = SettingsMaps.asMap(opts.get("fuzzy"));
        FuzzyOptions fo = null;
        if (fuzzy != null) {
            fo = new FuzzyOptions();
            Object fuzziness = fuzzy.get("fuzziness") == null ? "AUTO" : fuzzy.get("fuzziness");
            int edits;
            if ("AUTO".equalsIgnoreCase(String.valueOf(fuzziness))) {
                int len = normalizedPrefix.length();
                edits = len < 3 ? 0 : len < 6 ? 1 : 2;
            } else {
                edits = SearchEngine.intValue(fuzziness, 1);
            }
            fo.maxEdits(edits);
            fo.fuzzyPrefixLength(SearchEngine.intValue(fuzzy.get("prefix_length"), 1));
            fo.fuzzyMinLength(SearchEngine.intValue(fuzzy.get("min_length"), 3));
        }

        List<SegmentMatch> merged = new ArrayList<>();
        for (ShardTarget shard : shards) {
            List<com.naqqa.elasticsearch.search.execution.LeafReaderContext> leaves = shard.searcher.leafContexts();
            for (int s = 0; s < shard.segments.size(); s++) {
                SegmentReader segment = shard.segments.get(s);
                int base = leaves.get(s).docBase();
                CompletionSegmentIndex index = segment.completionIndex(field);
                List<CompletionSegmentIndex.Match> matches = fo != null
                    ? index.suggestFuzzy(normalizedPrefix, fo, null)
                    : index.suggest(normalizedPrefix, null);
                for (CompletionSegmentIndex.Match match : matches) {
                    if (!segment.isLive(match.localDocId())) {
                        continue;
                    }
                    merged.add(new SegmentMatch(shard, base + match.localDocId(), match.weight(), match.originalText()));
                }
            }
        }
        merged.sort(Comparator.comparingLong(SegmentMatch::weight).reversed());

        List<Map<String, Object>> options = new ArrayList<>();
        Set<String> seenDocs = new HashSet<>();
        Set<String> seenTexts = new HashSet<>();
        for (SegmentMatch match : merged) {
            String docKey = match.shard().ordinal + ":" + match.doc();
            if (!seenDocs.add(docKey)) {
                continue;
            }
            if (skipDuplicates && !seenTexts.add(match.original())) {
                continue;
            }
            StoredDocCodec.Decoded decoded = match.shard().fetch(match.doc());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("text", match.original());
            o.put("_index", match.shard().index());
            o.put("_id", decoded == null ? null : decoded.id());
            o.put("_score", (double) match.weight());
            if (decoded != null && decoded.source() != null) {
                o.put("_source", SearchEngine.sourceOf(decoded.source()));
            }
            options.add(o);
            if (options.size() >= size) {
                break;
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("text", prefix);
        entry.put("offset", 0);
        entry.put("length", prefix.length());
        entry.put("options", options);
        return List.of(entry);
    }
}
