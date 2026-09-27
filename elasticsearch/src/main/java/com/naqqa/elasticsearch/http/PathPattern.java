package com.naqqa.elasticsearch.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PathPattern {

    private sealed interface Segment {
    }

    private record Literal(String value) implements Segment {
    }

    private record Param(String name) implements Segment {
    }

    private record Wildcard() implements Segment {
    }

    private final String source;
    private final List<Segment> segments;
    private final boolean hasWildcard;

    private PathPattern(String source, List<Segment> segments, boolean hasWildcard) {
        this.source = source;
        this.segments = segments;
        this.hasWildcard = hasWildcard;
    }

    static PathPattern compile(String pattern) {
        List<Segment> segments = new ArrayList<>();
        boolean wildcard = false;
        List<String> tokens = split(pattern);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("*")) {
                if (i != tokens.size() - 1) {
                    throw new IllegalArgumentException("wildcard '*' must be the last segment in pattern: " + pattern);
                }
                segments.add(new Wildcard());
                wildcard = true;
            } else if (token.startsWith("{") && token.endsWith("}") && token.length() > 2) {
                segments.add(new Param(token.substring(1, token.length() - 1)));
            } else {
                segments.add(new Literal(token));
            }
        }
        return new PathPattern(pattern, segments, wildcard);
    }

    static List<String> split(String path) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        if (path.startsWith("/")) {
            start = 1;
        }
        if (start >= path.length()) {
            return tokens;
        }
        int i = start;
        int segStart = start;
        while (i <= path.length()) {
            if (i == path.length() || path.charAt(i) == '/') {
                if (i > segStart) {
                    tokens.add(path.substring(segStart, i));
                }
                segStart = i + 1;
            }
            i++;
        }
        return tokens;
    }

    MatchResult match(List<String> pathTokens) {
        Map<String, String> params = new LinkedHashMap<>();
        int score = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment instanceof Wildcard) {
                if (pathTokens.size() < i + 1) {
                    return null;
                }
                StringBuilder rest = new StringBuilder();
                for (int j = i; j < pathTokens.size(); j++) {
                    if (j > i) {
                        rest.append('/');
                    }
                    rest.append(pathTokens.get(j));
                }
                params.put("*", rest.toString());
                return new MatchResult(params, score);
            }
            if (i >= pathTokens.size()) {
                return null;
            }
            String token = pathTokens.get(i);
            if (segment instanceof Literal literal) {
                if (!literal.value().equals(token)) {
                    return null;
                }
                score += 100;
            } else if (segment instanceof Param param) {
                params.put(param.name(), token);
                score += 10;
            }
        }
        if (pathTokens.size() != segments.size()) {
            return null;
        }
        return new MatchResult(params, score);
    }

    boolean hasWildcard() {
        return hasWildcard;
    }

    String source() {
        return source;
    }

    record MatchResult(Map<String, String> pathParams, int score) {
    }
}
