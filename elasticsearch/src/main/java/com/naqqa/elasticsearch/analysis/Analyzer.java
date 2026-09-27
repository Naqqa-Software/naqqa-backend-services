package com.naqqa.elasticsearch.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class Analyzer implements AutoCloseable {

    public enum ReuseStrategy { GLOBAL, PER_FIELD }

    private final ReuseStrategy reuseStrategy;
    private ThreadLocal<Object> storage = new ThreadLocal<>();
    private ThreadLocal<TokenStreamComponents> normalizationStorage = new ThreadLocal<>();

    protected Analyzer() {
        this(ReuseStrategy.GLOBAL);
    }

    protected Analyzer(ReuseStrategy reuseStrategy) {
        this.reuseStrategy = reuseStrategy;
    }

    protected abstract TokenStreamComponents createComponents(String fieldName);

    protected TokenStreamComponents createNormalizationComponents(String fieldName) {
        return null;
    }

    @SuppressWarnings("unchecked")
    public TokenStream tokenStream(String fieldName, CharSequence text) {
        ThreadLocal<Object> local = storage;
        if (local == null) {
            throw new IllegalStateException("this Analyzer is closed");
        }
        TokenStreamComponents components;
        if (reuseStrategy == ReuseStrategy.GLOBAL) {
            components = (TokenStreamComponents) local.get();
            if (components == null) {
                components = createComponents(fieldName);
                local.set(components);
            }
        } else {
            Map<String, TokenStreamComponents> map = (Map<String, TokenStreamComponents>) local.get();
            if (map == null) {
                map = new HashMap<>();
                local.set(map);
            }
            String key = fieldName == null ? "" : fieldName;
            components = map.get(key);
            if (components == null) {
                components = createComponents(fieldName);
                map.put(key, components);
            }
        }
        components.setInput(text);
        return components.sink();
    }

    public String normalize(String fieldName, String text) {
        ThreadLocal<TokenStreamComponents> local = normalizationStorage;
        if (local == null) {
            throw new IllegalStateException("this Analyzer is closed");
        }
        TokenStreamComponents components = local.get();
        if (components == null) {
            components = createNormalizationComponents(fieldName);
            if (components == null) {
                return text;
            }
            local.set(components);
        }
        components.setInput(text);
        TokenStream ts = components.sink();
        StringBuilder sb = new StringBuilder();
        try {
            ts.reset();
            while (ts.incrementToken()) {
                Token t = ts.token();
                sb.append(t.buffer(), 0, t.length());
            }
            ts.end();
        } finally {
            ts.close();
        }
        return sb.toString();
    }

    public List<String> analyze(String fieldName, CharSequence text) {
        List<String> out = new ArrayList<>();
        TokenStream ts = tokenStream(fieldName, text);
        try {
            ts.reset();
            while (ts.incrementToken()) {
                out.add(ts.token().term());
            }
            ts.end();
        } finally {
            ts.close();
        }
        return out;
    }

    public int getPositionIncrementGap(String fieldName) {
        return 0;
    }

    public int getOffsetGap(String fieldName) {
        return 1;
    }

    @Override
    public void close() {
        storage = null;
        normalizationStorage = null;
    }
}
