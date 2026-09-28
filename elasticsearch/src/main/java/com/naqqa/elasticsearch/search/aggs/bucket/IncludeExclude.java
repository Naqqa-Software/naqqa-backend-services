package com.naqqa.elasticsearch.search.aggs.bucket;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class IncludeExclude {

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    private IncludeExclude() {
    }

    @SuppressWarnings("unchecked")
    public static Predicate<String> parse(Map<String, Object> params) {
        Predicate<String> includePredicate = buildPredicate(params.get("include"));
        Predicate<String> excludePredicate = buildPredicate(params.get("exclude"));
        if (includePredicate == null && excludePredicate == null) {
            return s -> true;
        }
        Predicate<String> include = includePredicate == null ? s -> true : includePredicate;
        Predicate<String> exclude = excludePredicate == null ? s -> false : excludePredicate;
        return s -> include.test(s) && !exclude.test(s);
    }

    private static Predicate<String> buildPredicate(Object spec) {
        if (spec == null) {
            return null;
        }
        if (spec instanceof String regex) {
            Pattern pattern = PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
            return s -> pattern.matcher(s).matches();
        }
        if (spec instanceof List<?> list) {
            Set<String> values = new HashSet<>();
            for (Object o : list) {
                values.add(String.valueOf(o));
            }
            return values::contains;
        }
        throw new IllegalArgumentException("include/exclude must be a regex string or a list of values, got " + spec.getClass());
    }
}
