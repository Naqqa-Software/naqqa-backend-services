package com.naqqa.elasticsearch.analysis.lang.c;

import com.naqqa.elasticsearch.analysis.Tokenizer;

public final class ThaiTokenizer extends Tokenizer {

    private int pos;

    public ThaiTokenizer() {
    }

    @Override
    public void reset() {
        super.reset();
        pos = 0;
    }

    private static boolean isThai(char c) {
        return c >= '฀' && c <= '๿';
    }

    private static boolean isCombining(char c) {
        return c == 'ั' || (c >= 'ิ' && c <= 'ฺ') || (c >= '็' && c <= '๎');
    }

    private static boolean isLeadingVowel(char c) {
        return c >= 'เ' && c <= 'ไ';
    }

    private static boolean isSkippable(char c) {
        return !isThai(c) && !Character.isLetterOrDigit(c);
    }

    @Override
    public boolean incrementToken() {
        CharSequence text = input;
        int len = text.length();
        while (pos < len && isSkippable(text.charAt(pos))) {
            pos++;
        }
        if (pos >= len) {
            return false;
        }
        int start = pos;
        char c = text.charAt(pos);
        int end;
        if (isThai(c)) {
            int matched = matchDictionary(text, pos, len);
            end = matched > 0 ? pos + matched : pos + clusterLength(text, pos, len);
        } else {
            end = pos;
            while (end < len && !isSkippable(text.charAt(end)) && !isThai(text.charAt(end))) {
                end++;
            }
        }
        token.clear();
        token.setTerm(text, start, end);
        token.setOffset(correctOffset(start), correctOffset(end));
        pos = end;
        return true;
    }

    private static int matchDictionary(CharSequence text, int start, int len) {
        int maxLen = Math.min(ThaiDictionary.maxWordLength(), len - start);
        for (int l = maxLen; l >= 1; l--) {
            if (ThaiDictionary.contains(text.subSequence(start, start + l).toString())) {
                return l;
            }
        }
        return 0;
    }

    private static int clusterLength(CharSequence text, int start, int len) {
        int i = start;
        if (i < len && isLeadingVowel(text.charAt(i))) {
            i++;
        }
        if (i < len && isThai(text.charAt(i))) {
            i++;
        } else {
            return Math.max(1, i - start);
        }
        while (i < len && isCombining(text.charAt(i))) {
            i++;
        }
        return i - start;
    }
}
