package com.naqqa.elasticsearch.rest.support;

import com.naqqa.elasticsearch.common.automaton.StringDistance;
import com.naqqa.elasticsearch.http.RestRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StrictParams {

    private StrictParams() {
    }

    public static final Set<String> GLOBAL_PARAMS = Set.of(
        "pretty", "human", "error_trace", "filter_path", "format", "source", "source_content_type");

    private static final double SUGGEST_THRESHOLD = 0.5d;

    public static void check(RestRequest request, Set<String> declaredParams) {
        Set<String> unrecognized = new LinkedHashSet<>();
        for (String name : request.queryParamNames()) {
            if (GLOBAL_PARAMS.contains(name) || declaredParams.contains(name) || request.consumedParams().contains(name)) {
                continue;
            }
            unrecognized.add(name);
        }
        if (unrecognized.isEmpty()) {
            return;
        }

        Set<String> candidates = new LinkedHashSet<>(declaredParams);
        candidates.addAll(request.consumedParams());
        candidates.addAll(GLOBAL_PARAMS);
        candidates.removeAll(unrecognized);
        List<String> suggestions = new ArrayList<>();
        for (String invalid : unrecognized) {
            String best = null;
            double bestScore = SUGGEST_THRESHOLD;
            for (String candidate : candidates) {
                double score = StringDistance.levenshteinSimilarity(invalid, candidate);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null && !suggestions.contains(best)) {
                suggestions.add(best);
            }
        }

        throw new IllegalArgumentException(buildMessage(request.path(), unrecognized, suggestions));
    }

    private static String buildMessage(String path, Set<String> unrecognized, List<String> suggestions) {
        StringBuilder sb = new StringBuilder();
        sb.append("request [").append(path).append("] contains unrecognized parameter");
        if (unrecognized.size() > 1) {
            sb.append('s');
        }
        sb.append(": ");
        appendBracketedList(sb, unrecognized);
        if (!suggestions.isEmpty()) {
            sb.append(" -> did you mean ");
            appendBracketedList(sb, suggestions);
            sb.append('?');
        }
        return sb.toString();
    }

    private static void appendBracketedList(StringBuilder sb, Iterable<String> values) {
        boolean first = true;
        for (String value : values) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('[').append(value).append(']');
            first = false;
        }
    }
}
