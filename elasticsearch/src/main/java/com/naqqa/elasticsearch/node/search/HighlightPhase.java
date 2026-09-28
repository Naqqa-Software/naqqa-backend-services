package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.highlight.BoundaryScanner;
import com.naqqa.elasticsearch.search.highlight.FastVectorHighlighter;
import com.naqqa.elasticsearch.search.highlight.HighlightRequest;
import com.naqqa.elasticsearch.search.highlight.Highlighter;
import com.naqqa.elasticsearch.search.highlight.HitContext;
import com.naqqa.elasticsearch.search.highlight.PlainHighlighter;
import com.naqqa.elasticsearch.search.highlight.UnifiedHighlighter;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.BoostQuery;
import com.naqqa.elasticsearch.search.query.ConstantScoreQuery;
import com.naqqa.elasticsearch.search.query.DisjunctionMaxQuery;
import com.naqqa.elasticsearch.search.query.FunctionScoreQuery;
import com.naqqa.elasticsearch.search.query.PhraseQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.TermQuery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class HighlightPhase {

    private static final Set<String> NESTED_QUERY_HOLDERS = Set.of("function_score", "script_score", "boosting", "nested",
        "has_child", "has_parent", "constant_score", "dis_max", "bool", "pinned");

    record Options(String type, String[] preTags, String[] postTags, int fragmentSize, int numberOfFragments, int noMatchSize,
                   Boolean requireFieldMatch, Map<String, Object> highlightQuery, String boundaryScanner, String order) {
    }

    static final class Terms {
        final Map<String, Set<String>> byField = new LinkedHashMap<>();

        void add(String field, String term) {
            byField.computeIfAbsent(field, f -> new LinkedHashSet<>()).add(term.toLowerCase(Locale.ROOT));
        }

        Set<String> forField(String field, boolean requireFieldMatch) {
            if (requireFieldMatch) {
                Set<String> direct = byField.get(field);
                return direct == null ? Set.of() : direct;
            }
            Set<String> all = new LinkedHashSet<>();
            for (Set<String> s : byField.values()) {
                all.addAll(s);
            }
            return all;
        }
    }

    private final Options global;
    private final Map<String, Options> fields = new LinkedHashMap<>();
    private final Function<Map<String, Object>, Query> toQuery;
    private final Map<Object, Terms> termsCache = new HashMap<>();

    @SuppressWarnings("unchecked")
    HighlightPhase(Map<String, Object> spec, Function<Map<String, Object>, Query> toQuery) {
        this.toQuery = toQuery;
        Options defaults = new Options("unified", new String[]{"<em>"}, new String[]{"</em>"}, 100, 5, 0, null, null, null, "none");
        if (spec.get("tags_schema") != null && "styled".equals(String.valueOf(spec.get("tags_schema")))) {
            defaults = new Options(defaults.type(), new String[]{"<em class=\"hlt1\">"}, defaults.postTags(), defaults.fragmentSize(),
                defaults.numberOfFragments(), defaults.noMatchSize(), null, null, null, "none");
        }
        this.global = parseOptions(spec, defaults);
        Object f = spec.get("fields");
        if (f instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Map<String, Object> opts = SettingsMaps.asMap(e.getValue());
                fields.put(String.valueOf(e.getKey()), parseOptions(opts == null ? Map.of() : opts, global));
            }
        } else if (f instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> entry = SettingsMaps.asMap(o);
                if (entry == null) {
                    continue;
                }
                for (Map.Entry<String, Object> e : entry.entrySet()) {
                    Map<String, Object> opts = SettingsMaps.asMap(e.getValue());
                    fields.put(e.getKey(), parseOptions(opts == null ? Map.of() : opts, global));
                }
            }
        }
        if (fields.isEmpty()) {
            throw new RestApiException(400, "[highlight] requires at least one field in [fields]");
        }
    }

    private static String[] tags(Object v, String[] fallback) {
        if (v == null) {
            return fallback;
        }
        List<String> list = SettingsMaps.asStringList(v instanceof List<?> ? v : List.of(v));
        return list.isEmpty() ? fallback : list.toArray(new String[0]);
    }

    private static Options parseOptions(Map<String, Object> m, Options parent) {
        String type = m.get("type") == null ? parent.type() : String.valueOf(m.get("type"));
        if (!Set.of("unified", "plain", "fvh").contains(type)) {
            throw new RestApiException(400, "unknown highlighter type [" + type + "]");
        }
        Boolean rfm = m.get("require_field_match") == null ? parent.requireFieldMatch()
            : Boolean.valueOf(String.valueOf(m.get("require_field_match")));
        return new Options(type, tags(m.get("pre_tags"), parent.preTags()), tags(m.get("post_tags"), parent.postTags()),
            SearchEngine.intValue(m.get("fragment_size"), parent.fragmentSize()),
            SearchEngine.intValue(m.get("number_of_fragments"), parent.numberOfFragments()),
            SearchEngine.intValue(m.get("no_match_size"), parent.noMatchSize()), rfm,
            m.get("highlight_query") != null ? SettingsMaps.asMap(m.get("highlight_query")) : parent.highlightQuery(),
            m.get("boundary_scanner") == null ? parent.boundaryScanner() : String.valueOf(m.get("boundary_scanner")),
            m.get("order") == null ? parent.order() : String.valueOf(m.get("order")));
    }

    Map<String, Object> highlight(ShardTarget shard, int doc, Query mainQuery, Map<String, Object> mainClause, Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Map<String, List<Object>> flat = new LinkedHashMap<>();
        FieldValues.flattenPaths("", source, flat);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Options> entry : fields.entrySet()) {
            String pattern = entry.getKey();
            Options opts = entry.getValue();
            List<String> concrete = new ArrayList<>();
            if (Regex.isSimpleMatchPattern(pattern)) {
                for (String path : flat.keySet()) {
                    if (Regex.simpleMatch(pattern, path)) {
                        QueryFactory.FieldType t = shard.fieldType(path);
                        if (t == null || "text".equals(t.type()) || "keyword".equals(t.type()) || "match_only_text".equals(t.type())) {
                            concrete.add(path);
                        }
                    }
                }
            } else {
                concrete.add(pattern);
            }
            boolean requireFieldMatch = opts.requireFieldMatch() == null ? true : opts.requireFieldMatch();
            Terms terms = opts.highlightQuery() != null ? terms(shard, opts.highlightQuery(), null)
                : terms(shard, mainClause, mainQuery);
            for (String field : concrete) {
                List<String> values = new ArrayList<>();
                for (Object v : FieldValues.fromSource(source, field)) {
                    if (!(v instanceof Map<?, ?>) && !(v instanceof List<?>)) {
                        values.add(String.valueOf(v));
                    }
                }
                if (values.isEmpty()) {
                    continue;
                }
                List<String> fragments = highlightField(shard, doc, field, values, terms.forField(field, requireFieldMatch), opts);
                if (!fragments.isEmpty()) {
                    out.put(field, fragments);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private List<String> highlightField(ShardTarget shard, int doc, String field, List<String> values, Set<String> terms, Options opts) {
        QueryFactory.FieldType type = shard.fieldType(field);
        Map<String, Object> def = mappingDefinition(shard, field);
        String pre = opts.preTags()[0];
        String post = opts.postTags()[0];
        List<String> fragments = new ArrayList<>();
        if (type != null && !"text".equals(type.type()) && !"match_only_text".equals(type.type())
            && !"search_as_you_type".equals(type.type())) {
            for (String value : values) {
                if (terms.contains(value.toLowerCase(Locale.ROOT))) {
                    fragments.add(pre + value + post);
                }
            }
            if (fragments.isEmpty() && opts.noMatchSize() > 0) {
                String v = values.get(0);
                fragments.add(v.length() > opts.noMatchSize() ? v.substring(0, opts.noMatchSize()) : v);
            }
            return fragments;
        }
        Analyzer analyzer = analyzerFor(shard, def);
        Highlighter highlighter = switch (opts.type()) {
            case "plain" -> new PlainHighlighter();
            case "fvh" -> new FastVectorHighlighter();
            default -> new UnifiedHighlighter();
        };
        boolean fvh = "fvh".equals(opts.type());
        if (fvh) {
            Object tv = def == null ? null : def.get("term_vector");
            if (tv == null || !String.valueOf(tv).contains("offsets")) {
                throw new RestApiException(400, "the field [" + field
                    + "] should be indexed with term vector with position offsets to be used with fast vector highlighter");
            }
        }
        List<TermVectorTerm> stored = fvh && values.size() == 1 ? storedTermVectors(shard, doc, field) : null;
        for (String value : values) {
            HighlightRequest request = new HighlightRequest(field).terms(terms).preTags(opts.preTags()).postTags(opts.postTags())
                .fragmentSize(opts.fragmentSize()).numberOfFragments(opts.numberOfFragments())
                .noMatchSize(fragments.isEmpty() && value.equals(values.get(values.size() - 1)) ? opts.noMatchSize() : 0)
                .boundaryScanner(boundary(opts.boundaryScanner()));
            HitContext context = new ValueHitContext(field, value, analyzer, fvh, stored);
            fragments.addAll(highlighter.highlight(request, context));
        }
        if (opts.numberOfFragments() > 0 && fragments.size() > opts.numberOfFragments()) {
            return new ArrayList<>(fragments.subList(0, opts.numberOfFragments()));
        }
        return fragments;
    }

    private static List<TermVectorTerm> storedTermVectors(ShardTarget shard, int doc, String field) {
        try {
            com.naqqa.elasticsearch.search.execution.LeafReaderContext ctx = shard.leafFor(doc);
            if (ctx == null) {
                return null;
            }
            TermVectorTerm[] tv = shard.segments.get(ctx.ord()).termVectors(field, doc - ctx.docBase());
            return tv == null || tv.length == 0 ? null : List.of(tv);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static BoundaryScanner boundary(String name) {
        if (name == null) {
            return BoundaryScanner.SENTENCE;
        }
        return switch (name) {
            case "chars" -> BoundaryScanner.CHARS;
            case "word" -> BoundaryScanner.WORD;
            default -> BoundaryScanner.SENTENCE;
        };
    }

    static Map<String, Object> mappingDefinition(ShardTarget shard, String field) {
        if (shard.indexService == null || shard.indexService.mapperService().documentMapper() == null) {
            return null;
        }
        Map<String, Object> mapping = SettingsMaps.asMap(shard.indexService.mapperService().documentMapper().mapping().toMapping().toJava());
        return SearchEngine.fieldDefinition(mapping, field);
    }

    static Analyzer analyzerFor(ShardTarget shard, Map<String, Object> def) {
        if (shard.indexService == null) {
            return null;
        }
        MapperService mapperService = shard.indexService.mapperService();
        String name = def == null ? null : def.get("analyzer") == null ? null : String.valueOf(def.get("analyzer"));
        if (name != null && mapperService.indexAnalyzers().has(name)) {
            return mapperService.indexAnalyzers().get(name);
        }
        return mapperService.indexAnalyzers().defaultAnalyzer();
    }

    static Analyzer searchAnalyzerFor(ShardTarget shard, Map<String, Object> def) {
        if (shard.indexService == null) {
            return null;
        }
        MapperService mapperService = shard.indexService.mapperService();
        Object name = def == null ? null : def.get("search_analyzer") != null ? def.get("search_analyzer") : def.get("analyzer");
        if (name != null && mapperService.indexAnalyzers().has(String.valueOf(name))) {
            return mapperService.indexAnalyzers().get(String.valueOf(name));
        }
        return mapperService.indexAnalyzers().defaultSearchAnalyzer();
    }

    private Terms terms(ShardTarget shard, Map<String, Object> clause, Query parsed) {
        Object key = List.of(shard.ordinal, clause == null ? Map.of() : clause);
        Terms cached = termsCache.get(key);
        if (cached != null) {
            return cached;
        }
        Terms terms = new Terms();
        Query query = parsed != null ? parsed : toQuery.apply(clause);
        collect(query, shard.searcher, terms, 0);
        if (clause != null) {
            collectNested(clause, shard, terms, 0);
        }
        termsCache.put(key, terms);
        return terms;
    }

    @SuppressWarnings("unchecked")
    private void collectNested(Object node, ShardTarget shard, Terms terms, int depth) {
        if (depth > 16 || !(node instanceof Map<?, ?> map)) {
            if (node instanceof List<?> list && depth <= 16) {
                for (Object o : list) {
                    collectNested(o, shard, terms, depth + 1);
                }
            }
            return;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (NESTED_QUERY_HOLDERS.contains(key) && e.getValue() instanceof Map<?, ?> body) {
                for (String sub : List.of("query", "positive", "filter", "organic")) {
                    Map<String, Object> inner = SettingsMaps.asMap(body.get(sub));
                    if (inner != null && !key.equals("bool")) {
                        try {
                            collect(toQuery.apply(inner), shard.searcher, terms, 0);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
            if (e.getValue() instanceof Map<?, ?> || e.getValue() instanceof List<?>) {
                collectNested(e.getValue(), shard, terms, depth + 1);
            }
        }
    }

    private static void collect(Query query, IndexSearcher searcher, Terms terms, int depth) {
        if (query == null || depth > 32) {
            return;
        }
        Query q = query;
        try {
            for (int i = 0; i < 8; i++) {
                Query next = q.rewrite(searcher);
                if (next == q || next == null) {
                    break;
                }
                q = next;
            }
        } catch (Exception ignored) {
        }
        if (q instanceof TermQuery tq) {
            terms.add(tq.term().field(), new String(tq.term().bytes(), StandardCharsets.UTF_8));
        } else if (q instanceof PhraseQuery pq) {
            for (byte[] t : pq.terms()) {
                terms.add(pq.field(), new String(t, StandardCharsets.UTF_8));
            }
        } else if (q instanceof BooleanQuery bq) {
            for (BooleanQuery.BooleanClause c : bq.clauses()) {
                if (c.occur() != BooleanQuery.Occur.MUST_NOT) {
                    collect(c.query(), searcher, terms, depth + 1);
                }
            }
        } else if (q instanceof BoostQuery bq) {
            collect(bq.inner(), searcher, terms, depth + 1);
        } else if (q instanceof ConstantScoreQuery csq) {
            collect(csq.inner(), searcher, terms, depth + 1);
        } else if (q instanceof DisjunctionMaxQuery dmq) {
            for (Query sub : dmq.subQueries()) {
                collect(sub, searcher, terms, depth + 1);
            }
        } else if (q instanceof FunctionScoreQuery fsq) {
            collect(fsq.inner(), searcher, terms, depth + 1);
        }
    }

    private static final class ValueHitContext implements HitContext {
        private final String field;
        private final String value;
        private final Analyzer analyzer;
        private final boolean termVectors;
        private final List<TermVectorTerm> stored;

        ValueHitContext(String field, String value, Analyzer analyzer, boolean termVectors, List<TermVectorTerm> stored) {
            this.field = field;
            this.value = value;
            this.analyzer = analyzer;
            this.termVectors = termVectors;
            this.stored = stored;
        }

        @Override
        public String getSourceField(String name) {
            return field.equals(name) ? value : null;
        }

        @Override
        public Map<String, Object> getSource() {
            return Map.of(field, value);
        }

        @Override
        public List<TermVectorTerm> getTermVectors(String name) {
            if (!termVectors || !field.equals(name)) {
                return null;
            }
            if (stored != null) {
                return stored;
            }
            if (analyzer == null) {
                return null;
            }
            Map<String, List<int[]>> occurrences = new LinkedHashMap<>();
            TokenStream ts = analyzer.tokenStream(name, value);
            int position = -1;
            try {
                ts.reset();
                while (ts.incrementToken()) {
                    Token t = ts.token();
                    position += Math.max(1, t.positionIncrement());
                    occurrences.computeIfAbsent(t.term(), k -> new ArrayList<>())
                        .add(new int[]{position, t.startOffset(), t.endOffset()});
                }
                ts.end();
            } finally {
                ts.close();
            }
            List<TermVectorTerm> out = new ArrayList<>();
            for (Map.Entry<String, List<int[]>> e : occurrences.entrySet()) {
                List<int[]> occ = e.getValue();
                int[] positions = new int[occ.size()];
                int[] starts = new int[occ.size()];
                int[] ends = new int[occ.size()];
                for (int i = 0; i < occ.size(); i++) {
                    positions[i] = occ.get(i)[0];
                    starts[i] = occ.get(i)[1];
                    ends[i] = occ.get(i)[2];
                }
                out.add(new TermVectorTerm(e.getKey().getBytes(StandardCharsets.UTF_8), occ.size(), positions, starts, ends));
            }
            return out;
        }

        @Override
        public Analyzer getAnalyzer(String name) {
            return analyzer;
        }
    }
}
