package com.naqqa.elasticsearch.analysis.stem.snowball;

final class NordicSupport {

    private NordicSupport() {
    }

    static boolean endsWith(StringBuilder w, int end, int limit, String s) {
        int n = s.length();
        int start = end - n;
        if (start < limit || start < 0) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (w.charAt(start + i) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    static int longest(StringBuilder w, int end, int limit, String[] suffixes) {
        int best = -1;
        int bestLen = -1;
        for (int k = 0; k < suffixes.length; k++) {
            String s = suffixes[k];
            int n = s.length();
            if (n > bestLen && endsWith(w, end, limit, s)) {
                best = k;
                bestLen = n;
            }
        }
        return best;
    }

    static boolean in(String group, char c) {
        return group.indexOf(c) >= 0;
    }

    static boolean charIn(StringBuilder w, int index, int limit, String group) {
        return index >= limit && index >= 0 && index < w.length() && group.indexOf(w.charAt(index)) >= 0;
    }

    static int scandinavianR1(StringBuilder w, String vowels) {
        int len = w.length();
        if (len < 3) {
            return len;
        }
        int i = 0;
        while (i < len && vowels.indexOf(w.charAt(i)) < 0) {
            i++;
        }
        if (i >= len) {
            return len;
        }
        while (i < len && vowels.indexOf(w.charAt(i)) >= 0) {
            i++;
        }
        if (i >= len) {
            return len;
        }
        int p1 = i + 1;
        return Math.max(p1, 3);
    }
}
