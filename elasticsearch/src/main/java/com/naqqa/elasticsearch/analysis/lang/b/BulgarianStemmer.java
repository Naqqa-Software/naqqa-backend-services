package com.naqqa.elasticsearch.analysis.lang.b;

public final class BulgarianStemmer extends AbstractCharStemmer {

    @Override
    int stem(char[] s, int len) {
        if (len < 4) {
            return len;
        }

        if (len > 5 && endsWith(s, len, "ища")) {
            return len - 3;
        }

        len = removeArticle(s, len);
        len = removePlural(s, len);

        if (len > 3) {
            if (endsWith(s, len, "я")) {
                len--;
            }
            if (endsWith(s, len, "а") || endsWith(s, len, "о") || endsWith(s, len, "е")) {
                len--;
            }
        }

        if (len > 4 && endsWith(s, len, "ен")) {
            s[len - 2] = 'н';
            len--;
        }

        if (len > 5 && s[len - 2] == 'ъ') {
            s[len - 2] = s[len - 1];
            len--;
        }

        return len;
    }

    private int removeArticle(char[] s, int len) {
        if (len > 6 && endsWith(s, len, "ият")) {
            return len - 3;
        }

        if (len > 5) {
            if (endsWith(s, len, "ът")
                || endsWith(s, len, "то")
                || endsWith(s, len, "те")
                || endsWith(s, len, "та")
                || endsWith(s, len, "ия")) {
                return len - 2;
            }
        }

        if (len > 4 && endsWith(s, len, "ят")) {
            return len - 2;
        }

        return len;
    }

    private int removePlural(char[] s, int len) {
        if (len > 6) {
            if (endsWith(s, len, "овци")) {
                return len - 3;
            }
            if (endsWith(s, len, "ове")) {
                return len - 3;
            }
            if (endsWith(s, len, "еве")) {
                s[len - 3] = 'й';
                return len - 2;
            }
        }

        if (len > 5) {
            if (endsWith(s, len, "ища")) {
                return len - 3;
            }
            if (endsWith(s, len, "та")) {
                return len - 2;
            }
            if (endsWith(s, len, "ци")) {
                s[len - 2] = 'к';
                return len - 1;
            }
            if (endsWith(s, len, "зи")) {
                s[len - 2] = 'г';
                return len - 1;
            }
            if (s[len - 3] == 'е' && s[len - 1] == 'и') {
                s[len - 3] = 'я';
                return len - 1;
            }
        }

        if (len > 4) {
            if (endsWith(s, len, "си")) {
                s[len - 2] = 'х';
                return len - 1;
            }
            if (endsWith(s, len, "и")) {
                return len - 1;
            }
        }

        return len;
    }
}
