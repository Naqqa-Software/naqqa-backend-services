package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class DecimalDigitFilter extends TokenFilter {

    public DecimalDigitFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() {
        if (!input.incrementToken()) {
            return false;
        }
        char[] buf = token.buffer();
        int len = token.length();
        for (int i = 0; i < len; i++) {
            int digit = Character.digit(buf[i], 10);
            if (digit >= 0 && Character.getType(buf[i]) == Character.DECIMAL_DIGIT_NUMBER) {
                buf[i] = (char) ('0' + digit);
            }
        }
        return true;
    }
}
