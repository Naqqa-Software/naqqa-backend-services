package com.naqqa.elasticsearch.search.suggest.phrase;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NGramLanguageModel {

    private final Map<String, Long> unigrams = new HashMap<>();
    private final Map<String, Long> bigrams = new HashMap<>();
    private long totalUnigrams;
    private long vocabularySize;
    private final double smoothing;
    private final double lambda;

    public NGramLanguageModel(Iterable<List<String>> sentences, double smoothing, double lambda) {
        this.smoothing = smoothing;
        this.lambda = lambda;
        for (List<String> sentence : sentences) {
            String prev = null;
            for (String raw : sentence) {
                String token = raw.toLowerCase(Locale.ROOT);
                unigrams.merge(token, 1L, Long::sum);
                totalUnigrams++;
                if (prev != null) {
                    bigrams.merge(key(prev, token), 1L, Long::sum);
                }
                prev = token;
            }
        }
        vocabularySize = unigrams.size();
    }

    private static String key(String a, String b) {
        return a + '\u0000' + b;
    }

    public double unigramProbability(String word) {
        long count = unigrams.getOrDefault(word.toLowerCase(Locale.ROOT), 0L);
        return (count + smoothing) / (totalUnigrams + smoothing * Math.max(1, vocabularySize));
    }

    public double bigramProbability(String prev, String word) {
        String p = prev.toLowerCase(Locale.ROOT);
        String w = word.toLowerCase(Locale.ROOT);
        long bicount = bigrams.getOrDefault(key(p, w), 0L);
        long unicount = unigrams.getOrDefault(p, 0L);
        if (unicount == 0) {
            return unigramProbability(w);
        }
        return (bicount + smoothing) / (unicount + smoothing * Math.max(1, vocabularySize));
    }

    public double interpolatedProbability(String prev, String word) {
        double uni = unigramProbability(word);
        if (prev == null) {
            return uni;
        }
        double bi = bigramProbability(prev, word);
        return lambda * bi + (1.0 - lambda) * uni;
    }

    public double logProbability(List<String> phrase) {
        double logProb = 0.0;
        String prev = null;
        for (String word : phrase) {
            double p = interpolatedProbability(prev, word);
            logProb += Math.log(Math.max(p, 1e-12));
            prev = word;
        }
        return logProb;
    }

    public long unigramCount(String word) {
        return unigrams.getOrDefault(word.toLowerCase(Locale.ROOT), 0L);
    }
}
