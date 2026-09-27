package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.analysis.AnalysisContext;
import com.naqqa.elasticsearch.analysis.AnalysisSettings;
import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.CharFilterFactory;
import com.naqqa.elasticsearch.analysis.FilteredText;
import com.naqqa.elasticsearch.analysis.OffsetCorrector;
import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilterFactory;
import com.naqqa.elasticsearch.analysis.TokenStream;
import com.naqqa.elasticsearch.analysis.Tokenizer;
import com.naqqa.elasticsearch.analysis.TokenizerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnalyzeAction {

    private final AnalysisContext context;

    public AnalyzeAction() {
        this(AnalysisContext.DEFAULT);
    }

    public AnalyzeAction(AnalysisContext context) {
        this.context = context;
    }

    public Map<String, Object> analyze(Map<String, Object> request) {
        return analyze(request, null);
    }

    public Map<String, Object> analyze(Map<String, Object> request, IndexAnalyzers indexAnalyzers) {
        AnalysisSettings req = AnalysisSettings.of(request);
        List<String> texts = extractTexts(req);
        boolean explain = req.getBoolean("explain", false);
        Set<String> attributes = req.getList("attributes") == null ? Set.of() : new java.util.HashSet<>(req.getList("attributes"));

        if (req.has("normalizer")) {
            String name = req.getString("normalizer");
            Analyzer normalizer = resolveNormalizer(name, indexAnalyzers);
            return runSimple(normalizer, texts);
        }
        if (req.has("analyzer")) {
            String name = req.getString("analyzer");
            Analyzer analyzer = resolveAnalyzer(name, indexAnalyzers);
            if (explain) {
                return explainAnalyzer(analyzer, texts, attributes);
            }
            return runSimple(analyzer, texts);
        }
        List<CharFilterFactory> charFilters = resolveCharFilters(req.get("char_filter"));
        TokenizerFactory tokenizer = resolveTokenizer(req.get("tokenizer"));
        List<TokenFilterFactory> filters = resolveTokenFilters(req.get("filter"));
        if (tokenizer == null) {
            Analyzer standard = BuiltinAnalyzers.get("standard");
            if (explain) {
                return explainAnalyzer(standard, texts, attributes);
            }
            return runSimple(standard, texts);
        }
        ComposedAnalyzer composed = new ComposedAnalyzer(charFilters, tokenizer, filters, 0);
        if (explain) {
            return explainAnalyzer(composed, texts, attributes);
        }
        return runSimple(composed, texts);
    }

    private Analyzer resolveAnalyzer(String name, IndexAnalyzers indexAnalyzers) {
        if (BuiltinAnalyzers.has(name)) {
            return BuiltinAnalyzers.get(name);
        }
        if (indexAnalyzers != null && indexAnalyzers.has(name)) {
            return indexAnalyzers.get(name);
        }
        throw new IllegalArgumentException("failed to find global analyzer under [" + name + "]");
    }

    private Analyzer resolveNormalizer(String name, IndexAnalyzers indexAnalyzers) {
        if (indexAnalyzers != null && indexAnalyzers.hasNormalizer(name)) {
            return indexAnalyzers.getNormalizer(name);
        }
        if ("lowercase".equals(name)) {
            AnalysisRegistry registry = new AnalysisRegistry(context);
            return registry.build(Map.of()).getNormalizer("lowercase");
        }
        throw new IllegalArgumentException("failed to find normalizer under [" + name + "]");
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTexts(AnalysisSettings req) {
        Object text = req.get("text");
        List<String> out = new ArrayList<>();
        if (text instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        } else if (text != null) {
            out.add(String.valueOf(text));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<CharFilterFactory> resolveCharFilters(Object raw) {
        List<CharFilterFactory> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object o : (List<Object>) raw) {
            if (o instanceof String s) {
                out.add(CharFilterFactories.create(s, s, AnalysisSettings.EMPTY, context));
            } else if (o instanceof Map<?, ?> m) {
                AnalysisSettings settings = AnalysisSettings.of((Map<String, Object>) m);
                String type = settings.getString("type");
                out.add(CharFilterFactories.create(type, type, settings, context));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private TokenizerFactory resolveTokenizer(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            return TokenizerFactories.create(s, s, AnalysisSettings.EMPTY, context);
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        AnalysisSettings settings = AnalysisSettings.of(m);
        String type = settings.getString("type");
        return TokenizerFactories.create(type, type, settings, context);
    }

    @SuppressWarnings("unchecked")
    private List<TokenFilterFactory> resolveTokenFilters(Object raw) {
        List<TokenFilterFactory> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object o : (List<Object>) raw) {
            if (o instanceof String s) {
                out.add(TokenFilterFactories.create(s, s, AnalysisSettings.EMPTY, context, name -> lookupByName(out, name)));
            } else if (o instanceof Map<?, ?> m) {
                AnalysisSettings settings = AnalysisSettings.of((Map<String, Object>) m);
                String type = settings.getString("type");
                out.add(TokenFilterFactories.create(type, type, settings, context, name -> lookupByName(out, name)));
            }
        }
        return out;
    }

    private TokenFilterFactory lookupByName(List<TokenFilterFactory> built, String name) {
        for (TokenFilterFactory f : built) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return TokenFilterFactories.create(name, name, AnalysisSettings.EMPTY, context, n -> lookupByName(built, n));
    }

    private Map<String, Object> runSimple(Analyzer analyzer, List<String> texts) {
        List<Map<String, Object>> tokens = new ArrayList<>();
        int offsetBase = 0;
        int position = -1;
        for (int idx = 0; idx < texts.size(); idx++) {
            String text = texts.get(idx);
            TokenStream ts = analyzer.tokenStream(null, text);
            try {
                ts.reset();
                boolean first = true;
                while (ts.incrementToken()) {
                    Token t = ts.token();
                    int inc = t.positionIncrement();
                    if (idx > 0 && first) {
                        inc += analyzer.getPositionIncrementGap(null);
                    }
                    position += inc;
                    tokens.add(tokenMap(t, position, offsetBase));
                    first = false;
                }
                ts.end();
            } finally {
                ts.close();
            }
            offsetBase += text.length() + analyzer.getOffsetGap(null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokens", tokens);
        return result;
    }

    private Map<String, Object> tokenMap(Token t, int position, int offsetBase) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", t.term());
        m.put("start_offset", t.startOffset() + offsetBase);
        m.put("end_offset", t.endOffset() + offsetBase);
        m.put("type", t.type());
        m.put("position", position);
        if (t.positionLength() != 1) {
            m.put("positionLength", t.positionLength());
        }
        return m;
    }

    private Map<String, Object> explainAnalyzer(Analyzer analyzer, List<String> texts, Set<String> attributes) {
        String text = String.join(" ", texts);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (!(analyzer instanceof ComposedAnalyzer composed)) {
            detail.put("custom_analyzer", false);
            Map<String, Object> tokenizerDetail = new LinkedHashMap<>();
            tokenizerDetail.put("name", "_analyzer_");
            tokenizerDetail.put("tokens", explainTokens(analyzer.tokenStream(null, text), attributes));
            detail.put("tokenizer", tokenizerDetail);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("detail", detail);
            return result;
        }
        detail.put("custom_analyzer", true);
        CharSequence current = text;
        OffsetCorrector corrector = OffsetCorrector.IDENTITY;
        List<Map<String, Object>> charFilterDetail = new ArrayList<>();
        for (CharFilterFactory cff : composed.charFilterFactories()) {
            CharFilter cf = cff.create();
            FilteredText out = new FilteredText();
            out.reset(corrector);
            cf.filter(current, out);
            current = out.text();
            corrector = out;
            Map<String, Object> cfEntry = new LinkedHashMap<>();
            cfEntry.put("name", cff.name());
            cfEntry.put("filtered_text", List.of(current.toString()));
            charFilterDetail.add(cfEntry);
        }
        if (!charFilterDetail.isEmpty()) {
            detail.put("charfilters", charFilterDetail);
        }
        Map<String, Object> tokenizerDetail = new LinkedHashMap<>();
        tokenizerDetail.put("name", composed.tokenizerFactory().name());
        tokenizerDetail.put("tokens", explainTokens(rerunTokenizer(composed, current, corrector), attributes));
        detail.put("tokenizer", tokenizerDetail);

        List<Map<String, Object>> filterDetails = new ArrayList<>();
        for (int i = 0; i < composed.tokenFilterFactories().size(); i++) {
            TokenStream stage = rerunUpTo(composed, current, corrector, i + 1);
            TokenFilterFactory f = composed.tokenFilterFactories().get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", f.name());
            entry.put("tokens", explainTokens(stage, attributes));
            filterDetails.add(entry);
        }
        if (!filterDetails.isEmpty()) {
            detail.put("tokenfilters", filterDetails);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detail", detail);
        return result;
    }

    private TokenStream rerunTokenizer(ComposedAnalyzer composed, CharSequence current, OffsetCorrector corrector) {
        Tokenizer t = composed.tokenizerFactory().create();
        t.setInput(current, corrector);
        return t;
    }

    private TokenStream rerunUpTo(ComposedAnalyzer composed, CharSequence current, OffsetCorrector corrector, int filterCount) {
        Tokenizer t = composed.tokenizerFactory().create();
        t.setInput(current, corrector);
        TokenStream s = t;
        for (int i = 0; i < filterCount; i++) {
            s = composed.tokenFilterFactories().get(i).create(s);
        }
        return s;
    }

    private List<Map<String, Object>> explainTokens(TokenStream ts, Set<String> attributes) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            ts.reset();
            int position = -1;
            while (ts.incrementToken()) {
                Token t = ts.token();
                position += t.positionIncrement();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("token", t.term());
                m.put("start_offset", t.startOffset());
                m.put("end_offset", t.endOffset());
                m.put("type", t.type());
                m.put("position", position);
                m.put("positionLength", t.positionLength());
                m.put("keyword", t.isKeyword());
                out.add(m);
            }
            ts.end();
        } finally {
            ts.close();
        }
        return out;
    }
}
