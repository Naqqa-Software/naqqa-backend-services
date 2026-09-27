package com.naqqa.elasticsearch.analysis.stem.light;

public final class FrenchMinimalStemmer extends CharArrayStemmer {

    public FrenchMinimalStemmer() {
    }

    @Override
    public int stem(char[] s, int len) {
        if (len < 6) {
            return len;
        }
        if (s[len - 1] == 'x') {
            if (s[len - 3] == 'a' && s[len - 2] == 'u') {
                s[len - 2] = 'l';
            }
            return len - 1;
        }
        if (s[len - 1] == 's') {
            len--;
        }
        if (s[len - 1] == 'r') {
            len--;
        }
        if (s[len - 1] == 'e') {
            len--;
        }
        if (s[len - 1] == 'é') {
            len--;
        }
        if (s[len - 1] == s[len - 2] && Character.isLetter(s[len - 1])) {
            len--;
        }
        return len;
    }
}
