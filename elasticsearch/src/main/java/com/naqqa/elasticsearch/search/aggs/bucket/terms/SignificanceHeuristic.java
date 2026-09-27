package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import java.util.Locale;

public enum SignificanceHeuristic {
    JLH,
    CHI_SQUARE;

    public static SignificanceHeuristic fromString(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "chi_square" -> CHI_SQUARE;
            case "jlh" -> JLH;
            default -> throw new IllegalArgumentException("unknown significance heuristic [" + s + "]");
        };
    }

    public double score(long subsetSize, long subsetDf, long supersetSize, long supersetDf) {
        if (subsetSize <= 0 || supersetSize <= 0 || subsetDf <= 0) {
            return 0;
        }
        return switch (this) {
            case JLH -> jlh(subsetSize, subsetDf, supersetSize, supersetDf);
            case CHI_SQUARE -> chiSquare(subsetSize, subsetDf, supersetSize, supersetDf);
        };
    }

    private static double jlh(long subsetSize, long subsetDf, long supersetSize, long supersetDf) {
        double subsetProbability = (double) subsetDf / subsetSize;
        double supersetProbability = Math.max((double) supersetDf / supersetSize, 1e-10);
        return subsetDf * subsetProbability * Math.log(subsetProbability / supersetProbability);
    }

    private static double chiSquare(long subsetSize, long subsetDf, long supersetSize, long supersetDf) {
        double n = supersetSize;
        double a = subsetDf;
        double b = subsetSize - subsetDf;
        double c = Math.max(0, supersetDf - subsetDf);
        double d = Math.max(0, (supersetSize - subsetSize) - c);
        double denom = (a + b) * (c + d) * (a + c) * (b + d);
        if (denom <= 0) {
            return 0;
        }
        double numerator = a * d - b * c;
        return n * numerator * numerator / denom;
    }
}
