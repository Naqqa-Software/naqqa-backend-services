package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.CharFilter;
import com.naqqa.elasticsearch.analysis.FilteredText;

public final class PersianCharFilter extends CharFilter {

    private static final char ZWNJ = '‌';

    @Override
    public void filter(CharSequence input, FilteredText output) {
        StringBuilder out = output.text();
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            out.append(c == ZWNJ ? ' ' : c);
        }
    }
}
