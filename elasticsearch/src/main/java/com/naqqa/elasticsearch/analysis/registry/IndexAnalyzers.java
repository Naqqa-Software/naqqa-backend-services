package com.naqqa.elasticsearch.analysis.registry;

import com.naqqa.elasticsearch.analysis.Analyzer;

import java.util.Map;

public final class IndexAnalyzers implements AutoCloseable {

    private final Map<String, Analyzer> analyzers;
    private final Map<String, Analyzer> normalizers;
    private final String defaultAnalyzerName;
    private final String defaultSearchAnalyzerName;

    public IndexAnalyzers(Map<String, Analyzer> analyzers, Map<String, Analyzer> normalizers,
                           String defaultAnalyzerName, String defaultSearchAnalyzerName) {
        this.analyzers = analyzers;
        this.normalizers = normalizers;
        this.defaultAnalyzerName = defaultAnalyzerName;
        this.defaultSearchAnalyzerName = defaultSearchAnalyzerName;
    }

    public Analyzer get(String name) {
        Analyzer a = analyzers.get(name);
        if (a == null) {
            throw new IllegalArgumentException("failed to find analyzer [" + name + "]");
        }
        return a;
    }

    public boolean has(String name) {
        return analyzers.containsKey(name);
    }

    public Analyzer getNormalizer(String name) {
        Analyzer a = normalizers.get(name);
        if (a == null) {
            throw new IllegalArgumentException("failed to find normalizer [" + name + "]");
        }
        return a;
    }

    public boolean hasNormalizer(String name) {
        return normalizers.containsKey(name);
    }

    public Analyzer defaultAnalyzer() {
        return get(defaultAnalyzerName);
    }

    public Analyzer defaultSearchAnalyzer() {
        return get(defaultSearchAnalyzerName);
    }

    @Override
    public void close() {
        for (Analyzer a : analyzers.values()) {
            a.close();
        }
        for (Analyzer a : normalizers.values()) {
            a.close();
        }
    }
}
