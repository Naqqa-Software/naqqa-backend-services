package com.naqqa.elasticsearch.search.suggest.term;

public interface StringDistance {

    double similarity(String a, String b);

    final class LevenshteinDistance implements StringDistance {
        @Override
        public double similarity(String a, String b) {
            int maxLen = Math.max(a.length(), b.length());
            if (maxLen == 0) {
                return 1.0;
            }
            int dist = DamerauLevenshtein.levenshtein(a, b);
            return 1.0 - ((double) dist / (double) maxLen);
        }
    }

    final class JaroWinklerDistance implements StringDistance {

        private static final double PREFIX_SCALE = 0.1;
        private static final int MAX_PREFIX = 4;

        @Override
        public double similarity(String a, String b) {
            if (a.equals(b)) {
                return 1.0;
            }
            int aLen = a.length();
            int bLen = b.length();
            if (aLen == 0 || bLen == 0) {
                return 0.0;
            }
            int matchDistance = Math.max(aLen, bLen) / 2 - 1;
            boolean[] aMatches = new boolean[aLen];
            boolean[] bMatches = new boolean[bLen];
            int matches = 0;
            for (int i = 0; i < aLen; i++) {
                int start = Math.max(0, i - matchDistance);
                int end = Math.min(bLen - 1, i + matchDistance);
                for (int j = start; j <= end; j++) {
                    if (bMatches[j] || a.charAt(i) != b.charAt(j)) {
                        continue;
                    }
                    aMatches[i] = true;
                    bMatches[j] = true;
                    matches++;
                    break;
                }
            }
            if (matches == 0) {
                return 0.0;
            }
            double transpositions = 0;
            int k = 0;
            for (int i = 0; i < aLen; i++) {
                if (!aMatches[i]) {
                    continue;
                }
                while (!bMatches[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
            transpositions /= 2.0;
            double m = matches;
            double jaro = ((m / aLen) + (m / bLen) + ((m - transpositions) / m)) / 3.0;
            int prefixLen = 0;
            int maxPrefix = Math.min(MAX_PREFIX, Math.min(aLen, bLen));
            while (prefixLen < maxPrefix && a.charAt(prefixLen) == b.charAt(prefixLen)) {
                prefixLen++;
            }
            return jaro + prefixLen * PREFIX_SCALE * (1.0 - jaro);
        }
    }
}
