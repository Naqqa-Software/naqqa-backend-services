package com.naqqa.elasticsearch.search.suggest.term;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

public final class TermSuggester {

    private final String[] sortedTerms;
    private final int[] frequencies;

    public TermSuggester(Iterable<String> corpus) {
        TreeMap<String, Integer> freq = new TreeMap<>();
        for (String term : corpus) {
            String normalized = term.toLowerCase(Locale.ROOT);
            freq.merge(normalized, 1, Integer::sum);
        }
        sortedTerms = freq.keySet().toArray(new String[0]);
        frequencies = new int[sortedTerms.length];
        int i = 0;
        for (int v : freq.values()) {
            frequencies[i++] = v;
        }
    }

    public int frequency(String term) {
        int idx = indexOf(term.toLowerCase(Locale.ROOT));
        return idx < 0 ? 0 : frequencies[idx];
    }

    public List<TermSuggestion> suggest(String word, TermSuggestOptions options) {
        String normalized = word.toLowerCase(Locale.ROOT);
        List<TermSuggestion> result = new ArrayList<>();
        if (normalized.length() < options.minWordLength()) {
            return result;
        }
        int originalFreq = frequency(normalized);
        if (options.suggestMode() == SuggestMode.MISSING && originalFreq > 0) {
            return result;
        }
        int lo = 0;
        int hi = sortedTerms.length;
        int prefixLen = Math.min(options.prefixLength(), normalized.length());
        if (prefixLen > 0) {
            String prefix = normalized.substring(0, prefixLen);
            lo = lowerBound(prefix);
            hi = upperBound(prefix);
        }
        int maxEdits = options.maxEdits();
        StringDistance distance = switch (options.distanceMetric()) {
            case JARO_WINKLER -> new StringDistance.JaroWinklerDistance();
            case LEVENSHTEIN -> new StringDistance.LevenshteinDistance();
            case INTERNAL -> null;
        };
        for (int i = lo; i < hi; i++) {
            String candidate = sortedTerms[i];
            if (candidate.equals(normalized)) {
                continue;
            }
            if (Math.abs(candidate.length() - normalized.length()) > maxEdits) {
                continue;
            }
            int dist = DamerauLevenshtein.boundedDistance(normalized, candidate, maxEdits);
            if (dist > maxEdits) {
                continue;
            }
            int candidateFreq = frequencies[i];
            if (options.suggestMode() == SuggestMode.POPULAR && candidateFreq <= originalFreq) {
                continue;
            }
            double score = distance == null ? -dist : distance.similarity(normalized, candidate);
            result.add(new TermSuggestion(candidate, candidateFreq, dist, score));
        }
        Comparator<TermSuggestion> comparator;
        if (options.distanceMetric() == DistanceMetric.INTERNAL) {
            comparator = Comparator.comparingInt(TermSuggestion::editDistance)
                .thenComparing(Comparator.comparingInt(TermSuggestion::frequency).reversed())
                .thenComparing(TermSuggestion::text);
        } else {
            comparator = Comparator.comparingDouble(TermSuggestion::score).reversed()
                .thenComparing(Comparator.comparingInt(TermSuggestion::frequency).reversed())
                .thenComparing(TermSuggestion::text);
        }
        result.sort(comparator);
        if (result.size() > options.size()) {
            result = result.subList(0, options.size());
        }
        return result;
    }

    private int indexOf(String term) {
        int lo = 0;
        int hi = sortedTerms.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = sortedTerms[mid].compareTo(term);
            if (cmp == 0) {
                return mid;
            } else if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    private int lowerBound(String prefix) {
        int lo = 0;
        int hi = sortedTerms.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedTerms[mid].compareTo(prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private int upperBound(String prefix) {
        String upper = prefix + Character.MAX_VALUE;
        int lo = 0;
        int hi = sortedTerms.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedTerms[mid].compareTo(upper) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
