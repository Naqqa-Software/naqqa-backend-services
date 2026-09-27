package com.naqqa.elasticsearch.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FilterPath {

    private static final Object DROPPED = new Object();

    private FilterPath() {
    }

    public static Object apply(Object tree, List<String> includes, List<String> excludes) {
        if (includes.isEmpty() && excludes.isEmpty()) {
            return tree;
        }
        List<String[]> includeParts = compile(includes);
        List<String[]> excludeParts = compile(excludes);
        Object result = filterValue(tree, new ArrayList<>(), includeParts, excludeParts, includeParts.isEmpty());
        return result == DROPPED ? Map.of() : result;
    }

    private static List<String[]> compile(List<String> patterns) {
        List<String[]> result = new ArrayList<>();
        for (String pattern : patterns) {
            result.add(pattern.split("\\."));
        }
        return result;
    }

    private static Object filterValue(Object value, List<String> path, List<String[]> includes, List<String[]> excludes, boolean fullyIncluded) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                path.add(key);
                boolean excluded = anyFullMatch(excludes, path);
                if (!excluded) {
                    boolean childFullyIncluded = fullyIncluded || anyFullMatch(includes, path);
                    if (childFullyIncluded || anyPrefixMatch(includes, path)) {
                        Object childValue = entry.getValue();
                        Object childResult = filterValue(childValue, path, includes, excludes, childFullyIncluded);
                        if (childResult != DROPPED && !emptiedBySpeculation(childFullyIncluded, childValue, childResult)) {
                            result.put(entry.getKey(), childResult);
                        }
                    }
                }
                path.remove(path.size() - 1);
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object element : list) {
                Object childResult = filterValue(element, path, includes, excludes, fullyIncluded);
                if (childResult != DROPPED && !emptiedBySpeculation(fullyIncluded, element, childResult)) {
                    result.add(childResult);
                }
            }
            return result;
        }
        return fullyIncluded ? value : DROPPED;
    }

    private static boolean emptiedBySpeculation(boolean childFullyIncluded, Object original, Object result) {
        if (childFullyIncluded) {
            return false;
        }
        boolean resultEmpty = (result instanceof Map<?, ?> m && m.isEmpty()) || (result instanceof List<?> l && l.isEmpty());
        if (!resultEmpty) {
            return false;
        }
        boolean originalEmpty = (original instanceof Map<?, ?> m && m.isEmpty()) || (original instanceof List<?> l && l.isEmpty());
        return !originalEmpty;
    }

    private static boolean anyFullMatch(List<String[]> patterns, List<String> path) {
        for (String[] pattern : patterns) {
            if (fullMatch(pattern, 0, path, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyPrefixMatch(List<String[]> patterns, List<String> path) {
        for (String[] pattern : patterns) {
            if (prefixMatch(pattern, 0, path, 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fullMatch(String[] pattern, int pi, List<String> path, int si) {
        if (pi == pattern.length) {
            return si == path.size();
        }
        String segment = pattern[pi];
        if (segment.equals("**")) {
            if (pi == pattern.length - 1) {
                return true;
            }
            for (int k = si; k <= path.size(); k++) {
                if (fullMatch(pattern, pi + 1, path, k)) {
                    return true;
                }
            }
            return false;
        }
        if (si == path.size()) {
            return false;
        }
        if (segment.equals("*") || segment.equals(path.get(si))) {
            return fullMatch(pattern, pi + 1, path, si + 1);
        }
        return false;
    }

    private static boolean prefixMatch(String[] pattern, int pi, List<String> path, int si) {
        if (si == path.size()) {
            return pi <= pattern.length;
        }
        if (pi == pattern.length) {
            return false;
        }
        String segment = pattern[pi];
        if (segment.equals("**")) {
            for (int k = si; k <= path.size(); k++) {
                if (prefixMatch(pattern, pi + 1, path, k)) {
                    return true;
                }
                if (k < path.size() && prefixMatch(pattern, pi, path, k + 1)) {
                    return true;
                }
            }
            return pi == pattern.length - 1;
        }
        if (segment.equals("*") || segment.equals(path.get(si))) {
            return prefixMatch(pattern, pi + 1, path, si + 1);
        }
        return false;
    }
}
