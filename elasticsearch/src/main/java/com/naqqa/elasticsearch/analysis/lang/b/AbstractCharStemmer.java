package com.naqqa.elasticsearch.analysis.lang.b;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

abstract class AbstractCharStemmer implements Stemmer {

    private static final ThreadLocal<char[]> BUFFER = ThreadLocal.withInitial(() -> new char[64]);

    abstract int stem(char[] s, int len);

    @Override
    public boolean stem(StringBuilder word) {
        int len = word.length();
        char[] buffer = BUFFER.get();
        if (buffer.length < len) {
            buffer = new char[Math.max(len, buffer.length << 1)];
            BUFFER.set(buffer);
        }
        word.getChars(0, len, buffer, 0);
        int newLen = stem(buffer, len);
        boolean changed = newLen != len;
        for (int i = 0; i < newLen; i++) {
            if (buffer[i] != word.charAt(i)) {
                word.setCharAt(i, buffer[i]);
                changed = true;
            }
        }
        if (newLen != len) {
            word.setLength(newLen);
        }
        return changed;
    }

    static boolean endsWith(char[] s, int len, String suffix) {
        int n = suffix.length();
        if (n > len) {
            return false;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (s[len - (n - i)] != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
