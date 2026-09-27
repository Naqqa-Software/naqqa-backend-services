package com.naqqa.elasticsearch.common.automaton;

import java.util.HashMap;
import java.util.Map;

public final class StringDistance {

    private StringDistance() {
    }

    public static int levenshtein(CharSequence a, CharSequence b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[m];
    }

    public static double levenshteinSimilarity(CharSequence a, CharSequence b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    public static int damerauLevenshtein(CharSequence a, CharSequence b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                char cb = b.charAt(j - 1);
                int cost = ca == cb ? 0 : 1;
                int val = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    val = Math.min(val, d[i - 2][j - 2] + cost);
                }
                d[i][j] = val;
            }
        }
        return d[n][m];
    }

    public static double jaro(CharSequence s1, CharSequence s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 && len2 == 0) {
            return 1.0;
        }
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int matchDistance = Math.max(0, Math.max(len1, len2) / 2 - 1);
        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(len2 - 1, i + matchDistance);
            for (int j = start; j <= end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        transpositions /= 2;
        double m = matches;
        return (m / len1 + m / len2 + (m - transpositions) / m) / 3.0;
    }

    public static double jaroWinkler(CharSequence s1, CharSequence s2) {
        return jaroWinkler(s1, s2, 0.1);
    }

    public static double jaroWinkler(CharSequence s1, CharSequence s2, double prefixScale) {
        double jaro = jaro(s1, s2);
        int maxPrefix = Math.min(4, Math.min(s1.length(), s2.length()));
        int prefix = 0;
        while (prefix < maxPrefix && s1.charAt(prefix) == s2.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * prefixScale * (1.0 - jaro);
    }

    public static double ngramSimilarity(CharSequence a, CharSequence b, int n) {
        if (a.length() == 0 && b.length() == 0) {
            return 1.0;
        }
        if (a.length() == 0 || b.length() == 0) {
            return 0.0;
        }
        Map<String, Integer> ga = ngramCounts(a, n);
        Map<String, Integer> gb = ngramCounts(b, n);
        int intersection = 0;
        int totalA = 0;
        int totalB = 0;
        for (Map.Entry<String, Integer> e : ga.entrySet()) {
            totalA += e.getValue();
            Integer other = gb.get(e.getKey());
            if (other != null) {
                intersection += Math.min(other, e.getValue());
            }
        }
        for (int v : gb.values()) {
            totalB += v;
        }
        return totalA + totalB == 0 ? 0.0 : (2.0 * intersection) / (totalA + totalB);
    }

    public static double ngramDistance(CharSequence a, CharSequence b, int n) {
        return 1.0 - ngramSimilarity(a, b, n);
    }

    private static Map<String, Integer> ngramCounts(CharSequence s, int n) {
        Map<String, Integer> counts = new HashMap<>();
        StringBuilder padded = new StringBuilder(s);
        while (padded.length() < n) {
            padded.append('\u0000');
        }
        for (int i = 0; i + n <= padded.length(); i++) {
            String g = padded.substring(i, i + n);
            counts.merge(g, 1, Integer::sum);
        }
        return counts;
    }
}
