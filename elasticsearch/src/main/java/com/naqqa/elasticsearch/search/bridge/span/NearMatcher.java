package com.naqqa.elasticsearch.search.bridge.span;

import java.util.List;

final class NearMatcher {

    private NearMatcher() {
    }

    static int[] bestMatch(List<List<int[]>> occurrencesPerClause, boolean inOrder, int slop) {
        for (List<int[]> occ : occurrencesPerClause) {
            if (occ.isEmpty()) {
                return null;
            }
        }
        return inOrder ? bestOrdered(occurrencesPerClause, slop) : bestUnordered(occurrencesPerClause, slop);
    }

    private static int[] bestOrdered(List<List<int[]>> occ, int slop) {
        int n = occ.size();
        List<int[]> first = occ.get(0);
        double[] dp = new double[first.size()];
        int[] firstStart = new int[first.size()];
        int[] curEnd = new int[first.size()];
        for (int j = 0; j < first.size(); j++) {
            dp[j] = 0;
            firstStart[j] = first.get(j)[0];
            curEnd[j] = first.get(j)[1];
        }
        for (int i = 1; i < n; i++) {
            List<int[]> clause = occ.get(i);
            double[] ndp = new double[clause.size()];
            int[] nFirstStart = new int[clause.size()];
            int[] nCurEnd = new int[clause.size()];
            for (int k = 0; k < clause.size(); k++) {
                double best = Double.POSITIVE_INFINITY;
                int bestFirst = -1;
                for (int m = 0; m < curEnd.length; m++) {
                    if (curEnd[m] <= clause.get(k)[0]) {
                        double cost = dp[m] + (clause.get(k)[0] - curEnd[m]);
                        if (cost < best) {
                            best = cost;
                            bestFirst = firstStart[m];
                        }
                    }
                }
                ndp[k] = best;
                nFirstStart[k] = bestFirst;
                nCurEnd[k] = clause.get(k)[1];
            }
            dp = ndp;
            firstStart = nFirstStart;
            curEnd = nCurEnd;
        }
        double bestCost = Double.POSITIVE_INFINITY;
        int bestIdx = -1;
        for (int j = 0; j < dp.length; j++) {
            if (dp[j] < bestCost) {
                bestCost = dp[j];
                bestIdx = j;
            }
        }
        if (bestIdx < 0 || bestCost > slop || firstStart[bestIdx] < 0) {
            return null;
        }
        return new int[] {firstStart[bestIdx], curEnd[bestIdx]};
    }

    private static int[] bestUnordered(List<List<int[]>> occ, int slop) {
        int n = occ.size();
        long product = 1;
        for (List<int[]> o : occ) {
            product *= o.size();
            if (product > 20000) {
                break;
            }
        }
        int[] bestRange = null;
        int bestSlopUsed = Integer.MAX_VALUE;
        if (product <= 20000) {
            int[] idx = new int[n];
            while (true) {
                int minStart = Integer.MAX_VALUE;
                int maxEnd = Integer.MIN_VALUE;
                int sumWidth = 0;
                for (int i = 0; i < n; i++) {
                    int[] o = occ.get(i).get(idx[i]);
                    minStart = Math.min(minStart, o[0]);
                    maxEnd = Math.max(maxEnd, o[1]);
                    sumWidth += o[1] - o[0];
                }
                int slopUsed = (maxEnd - minStart) - sumWidth;
                if (slopUsed >= 0 && slopUsed < bestSlopUsed) {
                    bestSlopUsed = slopUsed;
                    bestRange = new int[] {minStart, maxEnd};
                }
                int pos = n - 1;
                while (pos >= 0) {
                    idx[pos]++;
                    if (idx[pos] < occ.get(pos).size()) {
                        break;
                    }
                    idx[pos] = 0;
                    pos--;
                }
                if (pos < 0) {
                    break;
                }
            }
        } else {
            int minStart = Integer.MAX_VALUE;
            int maxEnd = Integer.MIN_VALUE;
            int sumWidth = 0;
            for (List<int[]> o : occ) {
                int[] first = o.get(0);
                minStart = Math.min(minStart, first[0]);
                maxEnd = Math.max(maxEnd, first[1]);
                sumWidth += first[1] - first[0];
            }
            bestSlopUsed = (maxEnd - minStart) - sumWidth;
            bestRange = new int[] {minStart, maxEnd};
        }
        if (bestRange == null || bestSlopUsed > slop) {
            return null;
        }
        return bestRange;
    }
}
