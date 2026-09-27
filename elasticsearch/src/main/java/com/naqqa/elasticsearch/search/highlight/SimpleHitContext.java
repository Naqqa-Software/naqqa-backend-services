package com.naqqa.elasticsearch.search.highlight;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SimpleHitContext implements HitContext {

    private final Map<String, Object> source = new HashMap<>();
    private final Map<String, List<TermVectorTerm>> termVectors = new HashMap<>();
    private final Map<String, Analyzer> analyzers = new HashMap<>();
    private Analyzer defaultAnalyzer;

    public SimpleHitContext source(String field, Object value) {
        source.put(field, value);
        return this;
    }

    public SimpleHitContext termVectors(String field, List<TermVectorTerm> terms) {
        termVectors.put(field, terms);
        return this;
    }

    public SimpleHitContext analyzer(String field, Analyzer analyzer) {
        analyzers.put(field, analyzer);
        return this;
    }

    public SimpleHitContext defaultAnalyzer(Analyzer analyzer) {
        this.defaultAnalyzer = analyzer;
        return this;
    }

    @Override
    public String getSourceField(String name) {
        Object v = source.get(name);
        return v == null ? null : v.toString();
    }

    @Override
    public Map<String, Object> getSource() {
        return source;
    }

    @Override
    public List<TermVectorTerm> getTermVectors(String field) {
        return termVectors.get(field);
    }

    @Override
    public Analyzer getAnalyzer(String field) {
        Analyzer a = analyzers.get(field);
        return a != null ? a : defaultAnalyzer;
    }
}
