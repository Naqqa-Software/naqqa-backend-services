package com.naqqa.elasticsearch.index.query;

import java.util.Objects;

public final class MinimumShouldMatch {

    private final String spec;

    private MinimumShouldMatch(String spec) {
        this.spec = spec;
    }

    public static MinimumShouldMatch parse(String spec) {
        if (spec == null || spec.isEmpty()) {
            throw QueryParseUtils.error("minimum_should_match value [{}] is invalid", spec);
        }
        for (String part : spec.trim().split("\\s+")) {
            validatePart(part);
        }
        return new MinimumShouldMatch(spec.trim());
    }

    public static MinimumShouldMatch of(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return new MinimumShouldMatch(String.valueOf(n.intValue()));
        }
        return parse(value.toString());
    }

    private static void validatePart(String part) {
        int lt = part.indexOf('<');
        if (lt >= 0) {
            String bound = part.substring(0, lt);
            String rest = part.substring(lt + 1);
            parseIntStrict(bound);
            validateSimple(rest);
        } else {
            validateSimple(part);
        }
    }

    private static void validateSimple(String s) {
        String numPart = s.endsWith("%") ? s.substring(0, s.length() - 1) : s;
        parseIntStrict(numPart);
    }

    private static int parseIntStrict(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw QueryParseUtils.error("minimum_should_match value [{}] is invalid", s);
        }
    }

    public String asString() {
        return spec;
    }

    public int resolve(int optionalClauseCount) {
        if (optionalClauseCount == 0) {
            return 0;
        }
        int result = optionalClauseCount;
        for (String part : spec.trim().split("\\s+")) {
            int lt = part.indexOf('<');
            if (lt >= 0) {
                int bound = parseIntStrict(part.substring(0, lt));
                if (optionalClauseCount > bound) {
                    result = calc(part.substring(lt + 1), optionalClauseCount);
                }
            } else {
                result = calc(part, optionalClauseCount);
            }
        }
        if (result < 0) {
            result = 0;
        }
        if (result > optionalClauseCount) {
            result = optionalClauseCount;
        }
        return result;
    }

    private static int calc(String spec, int total) {
        boolean percent = spec.endsWith("%");
        String numPart = percent ? spec.substring(0, spec.length() - 1) : spec;
        int value = parseIntStrict(numPart);
        if (percent) {
            boolean negative = value < 0;
            int abs = Math.abs(value);
            int computed = (abs * total) / 100;
            return negative ? total - computed : computed;
        }
        return value < 0 ? total + value : value;
    }

    @Override
    public String toString() {
        return spec;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MinimumShouldMatch other)) {
            return false;
        }
        return spec.equals(other.spec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spec);
    }
}
