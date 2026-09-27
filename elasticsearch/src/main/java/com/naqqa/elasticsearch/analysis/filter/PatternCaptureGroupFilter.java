package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatternCaptureGroupFilter extends TokenFilter {

    private final List<Pattern> patterns;
    private final boolean preserveOriginal;
    private final Deque<Token> pending = new ArrayDeque<>();

    public PatternCaptureGroupFilter(TokenStream input, List<Pattern> patterns, boolean preserveOriginal) {
        super(input);
        this.patterns = patterns;
        this.preserveOriginal = preserveOriginal;
    }

    @Override
    public void reset() {
        super.reset();
        pending.clear();
    }

    @Override
    public boolean incrementToken() {
        if (!pending.isEmpty()) {
            token.clear();
            token.copyFrom(pending.poll());
            token.setPositionIncrement(0);
            return true;
        }
        if (!input.incrementToken()) {
            return false;
        }
        String original = token.term();
        java.util.LinkedHashSet<String> matches = new java.util.LinkedHashSet<>();
        for (Pattern p : patterns) {
            Matcher m = p.matcher(original);
            int from = 0;
            while (from <= original.length() && m.find(from)) {
                for (int g = m.groupCount() >= 1 ? 1 : 0; g <= m.groupCount(); g++) {
                    String v = m.group(g);
                    if (v != null && !v.isEmpty()) {
                        matches.add(v);
                    }
                }
                from = m.end() > m.start() ? m.end() : m.end() + 1;
            }
        }
        if (preserveOriginal) {
            matches.remove(original);
        }
        Token base = token.copy();
        for (String m : matches) {
            Token t = base.copy();
            t.setTerm(m);
            pending.add(t);
        }
        if (preserveOriginal) {
            return true;
        }
        if (pending.isEmpty()) {
            token.setTerm(original);
            return true;
        }
        token.clear();
        token.copyFrom(pending.poll());
        return true;
    }
}
