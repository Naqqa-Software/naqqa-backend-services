package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

public final class CJKWidthFilter extends TokenFilter {

    public CJKWidthFilter(TokenStream input) {
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
            char c = buf[i];
            if (c >= 0xFF01 && c <= 0xFF5E) {
                buf[i] = (char) (c - 0xFEE0);
            } else if (c >= 0xFF66 && c <= 0xFF9D) {
                buf[i] = halfKatakanaToFull(c);
            } else if (c == 0xFF9E && i > 0 && buf[i - 1] >= 0x30A6 && buf[i - 1] <= 0x30FD) {
                buf[i - 1] = (char) (buf[i - 1] + 1);
                token.setLength(len - 1);
                System.arraycopy(buf, i + 1, buf, i, len - i - 1);
                len--;
                i--;
            } else if (c == 0xFF9F && i > 0 && buf[i - 1] >= 0x30CF && buf[i - 1] <= 0x30DD) {
                buf[i - 1] = (char) (buf[i - 1] + 2);
                token.setLength(len - 1);
                System.arraycopy(buf, i + 1, buf, i, len - i - 1);
                len--;
                i--;
            }
        }
        return true;
    }

    private static char halfKatakanaToFull(char c) {
        int idx = c - 0xFF66;
        char[] table = {
            0x30F2, 0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9, 0x30E3, 0x30E5, 0x30E7, 0x30C3,
            0x30FC, 0x30A2, 0x30A4, 0x30A6, 0x30A8, 0x30AA, 0x30AB, 0x30AD, 0x30AF, 0x30B1,
            0x30B3, 0x30B5, 0x30B7, 0x30B9, 0x30BB, 0x30BD, 0x30BF, 0x30C1, 0x30C4, 0x30C6,
            0x30C8, 0x30CA, 0x30CB, 0x30CC, 0x30CD, 0x30CE, 0x30CF, 0x30D2, 0x30D5, 0x30D8,
            0x30DB, 0x30DE, 0x30DF, 0x30E0, 0x30E1, 0x30E2, 0x30E4, 0x30E6, 0x30E8, 0x30E9,
            0x30EA, 0x30EB, 0x30EC, 0x30ED, 0x30EF, 0x30F3
        };
        if (idx < 0 || idx >= table.length) {
            return c;
        }
        return table[idx];
    }
}
