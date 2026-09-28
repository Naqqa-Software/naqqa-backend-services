package com.naqqa.elasticsearch.search.suggest.completion;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class FuzzyPrefixMatcher {

    private FuzzyPrefixMatcher() {
    }

    static byte[] requiredPrefixBytes(String query, FuzzyOptions options) {
        int[] q = units(query, options.unicodeAware());
        int k = q.length < options.fuzzyMinLength() ? q.length : Math.min(options.fuzzyPrefixLength(), q.length);
        return prefixString(query, k, options.unicodeAware()).getBytes(StandardCharsets.UTF_8);
    }

    private static String prefixString(String s, int unitCount, boolean unicodeAware) {
        if (unicodeAware) {
            int total = s.codePointCount(0, s.length());
            if (unitCount >= total) {
                return s;
            }
            return s.substring(0, s.offsetByCodePoints(0, unitCount));
        }
        if (unitCount >= s.length()) {
            return s;
        }
        return s.substring(0, unitCount);
    }

    static boolean matches(String query, String text, FuzzyOptions options) {
        int[] q = units(query, options.unicodeAware());
        int[] t = units(text, options.unicodeAware());
        if (q.length < options.fuzzyMinLength()) {
            return startsWithUnits(t, q);
        }
        int prefixLen = Math.min(options.fuzzyPrefixLength(), q.length);
        if (t.length < prefixLen) {
            return false;
        }
        for (int i = 0; i < prefixLen; i++) {
            if (q[i] != t[i]) {
                return false;
            }
        }
        int[] qSuffix = Arrays.copyOfRange(q, prefixLen, q.length);
        int maxEdits = options.maxEdits();
        int maxCols = Math.max(0, Math.min(t.length - prefixLen, qSuffix.length + maxEdits));
        int[] tSuffix = Arrays.copyOfRange(t, prefixLen, prefixLen + maxCols);
        return prefixEditDistance(qSuffix, tSuffix) <= maxEdits;
    }

    private static boolean startsWithUnits(int[] t, int[] q) {
        if (t.length < q.length) {
            return false;
        }
        for (int i = 0; i < q.length; i++) {
            if (t[i] != q[i]) {
                return false;
            }
        }
        return true;
    }

    private static int prefixEditDistance(int[] a, int[] b) {
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        int best = dp[n][0];
        for (int j = 1; j <= m; j++) {
            best = Math.min(best, dp[n][j]);
        }
        return best;
    }

    private static int[] units(String s, boolean unicodeAware) {
        if (unicodeAware) {
            return s.codePoints().toArray();
        }
        int[] out = new int[s.length()];
        for (int i = 0; i < s.length(); i++) {
            out[i] = s.charAt(i);
        }
        return out;
    }
}
