package com.naqqa.elasticsearch.analysis.stem.snowball;

import com.naqqa.elasticsearch.analysis.stem.Stemmer;

public abstract class SnowballSupport implements Stemmer {

    protected StringBuilder current;
    protected int cursor;
    protected int limit;
    protected int limitBackward;
    protected int bra;
    protected int ket;

    private char[] original = new char[32];

    protected SnowballSupport() {
    }

    @Override
    public final boolean stem(StringBuilder word) {
        int len = word.length();
        if (len == 0) {
            return false;
        }
        if (original.length < len) {
            original = new char[Math.max(len, original.length * 2)];
        }
        word.getChars(0, len, original, 0);
        current = word;
        cursor = 0;
        limit = len;
        limitBackward = 0;
        bra = 0;
        ket = len;
        try {
            run();
        } finally {
            current = null;
        }
        if (word.length() != len) {
            return true;
        }
        for (int i = 0; i < len; i++) {
            if (word.charAt(i) != original[i]) {
                return true;
            }
        }
        return false;
    }

    protected abstract void run();

    protected static final class Among {
        final String[] strings;
        final int[] results;

        private Among(String[] strings, int[] results) {
            this.strings = strings;
            this.results = results;
        }

        public static Among of(String[]... groups) {
            int n = 0;
            for (String[] g : groups) {
                n += g.length;
            }
            String[] s = new String[n];
            int[] r = new int[n];
            int k = 0;
            for (int gi = 0; gi < groups.length; gi++) {
                for (String str : groups[gi]) {
                    s[k] = str;
                    r[k] = gi + 1;
                    k++;
                }
            }
            for (int i = 1; i < n; i++) {
                String ts = s[i];
                int tr = r[i];
                int j = i - 1;
                while (j >= 0 && s[j].length() < ts.length()) {
                    s[j + 1] = s[j];
                    r[j + 1] = r[j];
                    j--;
                }
                s[j + 1] = ts;
                r[j + 1] = tr;
            }
            return new Among(s, r);
        }
    }

    protected static String[] g(String... s) {
        return s;
    }

    protected final int findAmong(Among among) {
        String[] s = among.strings;
        int c = cursor;
        int available = limit - c;
        for (int i = 0; i < s.length; i++) {
            String str = s[i];
            int n = str.length();
            if (n > available) {
                continue;
            }
            int k = 0;
            while (k < n && current.charAt(c + k) == str.charAt(k)) {
                k++;
            }
            if (k == n) {
                cursor = c + n;
                return among.results[i];
            }
        }
        return 0;
    }

    protected final int findAmongB(Among among) {
        String[] s = among.strings;
        int c = cursor;
        int available = c - limitBackward;
        for (int i = 0; i < s.length; i++) {
            String str = s[i];
            int n = str.length();
            if (n > available) {
                continue;
            }
            int start = c - n;
            int k = 0;
            while (k < n && current.charAt(start + k) == str.charAt(k)) {
                k++;
            }
            if (k == n) {
                cursor = start;
                return among.results[i];
            }
        }
        return 0;
    }

    protected static boolean in(String grouping, char ch) {
        return grouping.indexOf(ch) >= 0;
    }

    protected final boolean inGrouping(String grouping) {
        if (cursor >= limit || !in(grouping, current.charAt(cursor))) {
            return false;
        }
        cursor++;
        return true;
    }

    protected final boolean outGrouping(String grouping) {
        if (cursor >= limit || in(grouping, current.charAt(cursor))) {
            return false;
        }
        cursor++;
        return true;
    }

    protected final boolean inGroupingB(String grouping) {
        if (cursor <= limitBackward || !in(grouping, current.charAt(cursor - 1))) {
            return false;
        }
        cursor--;
        return true;
    }

    protected final boolean outGroupingB(String grouping) {
        if (cursor <= limitBackward || in(grouping, current.charAt(cursor - 1))) {
            return false;
        }
        cursor--;
        return true;
    }

    protected final boolean goPastIn(String grouping) {
        while (cursor < limit) {
            char ch = current.charAt(cursor++);
            if (in(grouping, ch)) {
                return true;
            }
        }
        return false;
    }

    protected final boolean goPastOut(String grouping) {
        while (cursor < limit) {
            char ch = current.charAt(cursor++);
            if (!in(grouping, ch)) {
                return true;
            }
        }
        return false;
    }

    protected final boolean next() {
        if (cursor >= limit) {
            return false;
        }
        cursor++;
        return true;
    }

    protected final boolean nextB() {
        if (cursor <= limitBackward) {
            return false;
        }
        cursor--;
        return true;
    }

    protected final boolean hop(int n) {
        int c = cursor + n;
        if (c > limit) {
            return false;
        }
        cursor = c;
        return true;
    }

    protected final boolean hopB(int n) {
        int c = cursor - n;
        if (c < limitBackward) {
            return false;
        }
        cursor = c;
        return true;
    }

    protected final boolean eqS(String s) {
        int n = s.length();
        if (limit - cursor < n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (current.charAt(cursor + i) != s.charAt(i)) {
                return false;
            }
        }
        cursor += n;
        return true;
    }

    protected final boolean eqSB(String s) {
        int n = s.length();
        if (cursor - limitBackward < n) {
            return false;
        }
        int start = cursor - n;
        for (int i = 0; i < n; i++) {
            if (current.charAt(start + i) != s.charAt(i)) {
                return false;
            }
        }
        cursor = start;
        return true;
    }

    protected final void sliceFrom(String s) {
        int adjustment = s.length() - (ket - bra);
        current.replace(bra, ket, s);
        limit += adjustment;
        if (cursor >= ket) {
            cursor += adjustment;
        } else if (cursor > bra) {
            cursor = bra;
        }
    }

    protected final void sliceDel() {
        sliceFrom("");
    }
}
