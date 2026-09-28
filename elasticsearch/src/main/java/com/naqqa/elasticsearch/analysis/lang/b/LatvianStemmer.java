package com.naqqa.elasticsearch.analysis.lang.b;

public final class LatvianStemmer extends AbstractCharStemmer {

    @Override
    int stem(char[] s, int len) {
        int numVowels = numVowels(s, len);

        for (Affix affix : AFFIXES) {
            if (numVowels > affix.vc && len >= affix.affix.length + 3 && endsWith(s, len, affix.affix)) {
                len -= affix.affix.length;
                return affix.palatalizes ? unpalatalize(s, len) : len;
            }
        }

        return len;
    }

    private static final Affix[] AFFIXES = {
        new Affix("ajiem", 3, false), new Affix("ajai", 3, false),
        new Affix("ajam", 2, false), new Affix("ajām", 2, false),
        new Affix("ajos", 2, false), new Affix("ajās", 2, false),
        new Affix("iem", 2, true), new Affix("ajā", 2, false),
        new Affix("ais", 2, false), new Affix("ai", 2, false),
        new Affix("ei", 2, false), new Affix("ām", 1, false),
        new Affix("am", 1, false), new Affix("ēm", 1, false),
        new Affix("īm", 1, false), new Affix("im", 1, false),
        new Affix("um", 1, false), new Affix("us", 1, true),
        new Affix("as", 1, false), new Affix("ās", 1, false),
        new Affix("es", 1, false), new Affix("os", 1, true),
        new Affix("ij", 1, false), new Affix("īs", 1, false),
        new Affix("ēs", 1, false), new Affix("is", 1, false),
        new Affix("ie", 1, false), new Affix("u", 1, true),
        new Affix("a", 1, true), new Affix("i", 1, true),
        new Affix("e", 1, false), new Affix("ā", 1, false),
        new Affix("ē", 1, false), new Affix("ī", 1, false),
        new Affix("ū", 1, false), new Affix("o", 1, false),
        new Affix("s", 0, false), new Affix("š", 0, false),
    };

    private static final class Affix {
        final char[] affix;
        final int vc;
        final boolean palatalizes;

        Affix(String affix, int vc, boolean palatalizes) {
            this.affix = affix.toCharArray();
            this.vc = vc;
            this.palatalizes = palatalizes;
        }
    }

    private static boolean endsWith(char[] s, int len, char[] suffix) {
        return endsWith(s, len, new String(suffix));
    }

    private int unpalatalize(char[] s, int len) {
        if (s[len] == 'u') {
            if (endsWith(s, len, "kš")) {
                len++;
                s[len - 2] = 's';
                s[len - 1] = 't';
                return len;
            }
            if (endsWith(s, len, "ņņ")) {
                s[len - 2] = 'n';
                s[len - 1] = 'n';
                return len;
            }
        }

        if (endsWith(s, len, "pj") || endsWith(s, len, "bj") || endsWith(s, len, "mj") || endsWith(s, len, "vj")) {
            return len - 1;
        } else if (endsWith(s, len, "šņ")) {
            s[len - 2] = 's';
            s[len - 1] = 'n';
            return len;
        } else if (endsWith(s, len, "žņ")) {
            s[len - 2] = 'z';
            s[len - 1] = 'n';
            return len;
        } else if (endsWith(s, len, "šļ")) {
            s[len - 2] = 's';
            s[len - 1] = 'l';
            return len;
        } else if (endsWith(s, len, "žļ")) {
            s[len - 2] = 'z';
            s[len - 1] = 'l';
            return len;
        } else if (endsWith(s, len, "ļņ")) {
            s[len - 2] = 'l';
            s[len - 1] = 'n';
            return len;
        } else if (endsWith(s, len, "ļļ")) {
            s[len - 2] = 'l';
            s[len - 1] = 'l';
            return len;
        } else if (s[len - 1] == 'č') {
            s[len - 1] = 'c';
            return len;
        } else if (s[len - 1] == 'ļ') {
            s[len - 1] = 'l';
            return len;
        } else if (s[len - 1] == 'ņ') {
            s[len - 1] = 'n';
            return len;
        }

        return len;
    }

    private int numVowels(char[] s, int len) {
        int n = 0;
        for (int i = 0; i < len; i++) {
            switch (s[i]) {
                case 'a', 'e', 'i', 'o', 'u', 'ā', 'ī', 'ē', 'ū':
                    n++;
                default:
            }
        }
        return n;
    }
}
