package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.List;
import java.util.Map;

public final class StemmerOverrideFilter extends TokenFilter {

    private final Map<String, String> overrides;

    public StemmerOverrideFilter(TokenStream input, Map<String, String> overrides) {
        super(input);
        this.overrides = overrides;
    }

    public static Map<String, String> parseRules(List<String> rules) {
        Map<String, String> out = new java.util.HashMap<>();
        if (rules == null) {
            return out;
        }
        for (String rule : rules) {
            int arrow = rule.indexOf("=>");
            if (arrow < 0) {
                throw new IllegalArgumentException("Invalid stemmer override rule: [" + rule + "]");
            }
            String rhs = rule.substring(arrow + 2).trim();
            for (String lhs : rule.substring(0, arrow).split(",")) {
                out.put(lhs.trim(), rhs);
            }
        }
        return out;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        if (!token.isKeyword()) {
            String override = overrides.get(token.term());
            if (override != null) {
                token.setTerm(override);
                token.setKeyword(true);
            }
        }
        return true;
    }
}
