package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.Locale;
import java.util.Set;

public final class ElisionFilter extends TokenFilter {

    private static final char[] APOSTROPHES = {'\'', '’', 'ʼ'};

    private final Set<String> articles;

    public ElisionFilter(TokenStream input, Set<String> articles) {
        super(input);
        this.articles = articles;
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        if (token.isKeyword()) {
            return true;
        }
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; i++) {
            if (isApostrophe(buf[i])) {
                String prefix = new String(buf, 0, i).toLowerCase(Locale.ROOT);
                if (articles.contains(prefix)) {
                    token.setTerm(buf, i + 1, len - i - 1);
                }
                return true;
            }
        }
        return true;
    }

    private static boolean isApostrophe(char c) {
        for (char a : APOSTROPHES) {
            if (a == c) {
                return true;
            }
        }
        return false;
    }
}
