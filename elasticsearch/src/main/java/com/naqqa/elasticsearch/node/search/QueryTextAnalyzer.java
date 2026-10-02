package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.analysis.Analyzer;
import com.naqqa.elasticsearch.node.support.SettingsMaps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class QueryTextAnalyzer {

    private static final int MAX_DEPTH = 64;
    private static final Set<String> SINGLE_FIELD = Set.of("match", "match_phrase", "match_phrase_prefix", "match_bool_prefix");
    private static final Set<String> BOOL_OCCURS = Set.of("must", "filter", "should", "must_not");
    private static final Set<String> WRAPPED_QUERY = Set.of("function_score", "nested", "has_child", "has_parent", "script_score");
    private static final Set<String> MATCH_PARAMS = Set.of("operator", "fuzziness", "prefix_length", "max_expansions",
        "fuzzy_transpositions", "minimum_should_match", "zero_terms_query", "lenient");

    private QueryTextAnalyzer() {
    }

    static Map<String, Object> rewrite(Map<String, Object> clause, Function<String, Analyzer> analyzers) {
        if (clause == null || analyzers == null) {
            return clause;
        }
        return rewrite(clause, analyzers, 0);
    }

    private static Map<String, Object> rewrite(Map<String, Object> clause, Function<String, Analyzer> analyzers, int depth) {
        if (clause == null || clause.size() != 1 || depth > MAX_DEPTH) {
            return clause;
        }
        String key = clause.keySet().iterator().next();
        Map<String, Object> body = SettingsMaps.asMap(clause.get(key));
        if (body == null) {
            return clause;
        }
        Map<String, Object> rewritten;
        if (SINGLE_FIELD.contains(key)) {
            rewritten = rewriteSingleField(body, analyzers);
        } else if ("multi_match".equals(key)) {
            Map<String, Object> expanded = rewriteMultiMatch(body, analyzers);
            if (expanded != null && expanded.size() == 1 && !expanded.containsKey("multi_match")) {
                return expanded;
            }
            rewritten = expanded == null ? null : SettingsMaps.asMap(expanded.get("multi_match"));
        } else if ("bool".equals(key)) {
            rewritten = new LinkedHashMap<>(body);
            for (String occur : BOOL_OCCURS) {
                if (body.containsKey(occur)) {
                    rewritten.put(occur, rewriteClauses(body.get(occur), analyzers, depth));
                }
            }
        } else if ("dis_max".equals(key)) {
            rewritten = new LinkedHashMap<>(body);
            if (body.containsKey("queries")) {
                rewritten.put("queries", rewriteClauses(body.get("queries"), analyzers, depth));
            }
        } else if ("constant_score".equals(key)) {
            rewritten = new LinkedHashMap<>(body);
            if (body.containsKey("filter")) {
                rewritten.put("filter", rewriteClauses(body.get("filter"), analyzers, depth));
            }
        } else if ("boosting".equals(key)) {
            rewritten = new LinkedHashMap<>(body);
            for (String part : List.of("positive", "negative")) {
                if (body.containsKey(part)) {
                    rewritten.put(part, rewriteClauses(body.get(part), analyzers, depth));
                }
            }
        } else if (WRAPPED_QUERY.contains(key)) {
            rewritten = new LinkedHashMap<>(body);
            if (body.containsKey("query")) {
                rewritten.put("query", rewriteClauses(body.get("query"), analyzers, depth));
            }
        } else {
            return clause;
        }
        if (rewritten == null) {
            return clause;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, rewritten);
        return out;
    }

    private static Object rewriteClauses(Object value, Function<String, Analyzer> analyzers, int depth) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                Map<String, Object> m = SettingsMaps.asMap(o);
                out.add(m == null ? o : rewrite(m, analyzers, depth + 1));
            }
            return out;
        }
        Map<String, Object> m = SettingsMaps.asMap(value);
        return m == null ? value : rewrite(m, analyzers, depth + 1);
    }

    private static Map<String, Object> rewriteSingleField(Map<String, Object> body, Function<String, Analyzer> analyzers) {
        String field = null;
        for (String k : body.keySet()) {
            if (!k.equals("boost") && !k.equals("_name")) {
                field = k;
                break;
            }
        }
        if (field == null) {
            return null;
        }
        Object raw = body.get(field);
        Map<String, Object> spec = SettingsMaps.asMap(raw);
        if (spec != null && spec.get("analyzer") != null) {
            return null;
        }
        Object text = spec != null ? spec.get("query") : raw;
        if (!(text instanceof String s)) {
            return null;
        }
        String analyzed = analyze(analyzers, field, s);
        if (analyzed == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(body);
        if (spec != null) {
            Map<String, Object> newSpec = new LinkedHashMap<>(spec);
            newSpec.put("query", analyzed);
            out.put(field, newSpec);
        } else {
            out.put(field, analyzed);
        }
        return out;
    }

    private static Map<String, Object> rewriteMultiMatch(Map<String, Object> body, Function<String, Analyzer> analyzers) {
        if (body.get("analyzer") != null || !(body.get("query") instanceof String text)) {
            return null;
        }
        List<String> fieldSpecs = SettingsMaps.asStringList(body.get("fields"));
        if (fieldSpecs == null || fieldSpecs.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        List<Float> boosts = new ArrayList<>();
        for (String spec : fieldSpecs) {
            int caret = spec.indexOf('^');
            String name = caret >= 0 ? spec.substring(0, caret) : spec;
            if (name.contains("*")) {
                return null;
            }
            names.add(name);
            boosts.add(caret >= 0 ? Float.parseFloat(spec.substring(caret + 1)) : null);
        }
        List<String> analyzed = new ArrayList<>(names.size());
        boolean same = true;
        for (String name : names) {
            String a = analyze(analyzers, name, text);
            if (a == null) {
                return null;
            }
            if (!analyzed.isEmpty() && !analyzed.get(0).equals(a)) {
                same = false;
            }
            analyzed.add(a);
        }
        String type = body.get("type") == null ? "best_fields" : String.valueOf(body.get("type"));
        if (same || "cross_fields".equals(type) || "combined_fields".equals(type)) {
            Map<String, Object> newBody = new LinkedHashMap<>(body);
            newBody.put("query", analyzed.get(0));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("multi_match", newBody);
            return out;
        }
        String queryType = switch (type) {
            case "phrase" -> "match_phrase";
            case "phrase_prefix" -> "match_phrase_prefix";
            case "bool_prefix" -> "match_bool_prefix";
            default -> "match";
        };
        List<Object> perField = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("query", analyzed.get(i));
            if ("match".equals(queryType) || "match_bool_prefix".equals(queryType)) {
                for (String p : MATCH_PARAMS) {
                    if (body.containsKey(p) && ("match".equals(queryType) || "operator".equals(p) || "max_expansions".equals(p))) {
                        spec.put(p, body.get(p));
                    }
                }
            } else {
                for (String p : List.of("slop", "max_expansions", "zero_terms_query")) {
                    if (body.containsKey(p) && !("match_phrase".equals(queryType) && "max_expansions".equals(p))) {
                        spec.put(p, body.get(p));
                    }
                }
            }
            if (boosts.get(i) != null) {
                spec.put("boost", boosts.get(i));
            }
            Map<String, Object> fieldBody = new LinkedHashMap<>();
            fieldBody.put(names.get(i), spec);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put(queryType, fieldBody);
            perField.add(q);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if ("most_fields".equals(type)) {
            Map<String, Object> bool = new LinkedHashMap<>();
            bool.put("should", perField);
            bool.put("minimum_should_match", 1);
            if (body.get("boost") != null) {
                bool.put("boost", body.get("boost"));
            }
            out.put("bool", bool);
        } else {
            Map<String, Object> disMax = new LinkedHashMap<>();
            disMax.put("queries", perField);
            if (body.get("tie_breaker") != null) {
                disMax.put("tie_breaker", body.get("tie_breaker"));
            }
            if (body.get("boost") != null) {
                disMax.put("boost", body.get("boost"));
            }
            out.put("dis_max", disMax);
        }
        return out;
    }

    private static String analyze(Function<String, Analyzer> analyzers, String field, String text) {
        Analyzer analyzer;
        try {
            analyzer = analyzers.apply(field);
        } catch (RuntimeException e) {
            return null;
        }
        if (analyzer == null) {
            return null;
        }
        List<String> tokens;
        try {
            tokens = analyzer.analyze(field, text);
        } catch (RuntimeException e) {
            return null;
        }
        for (String t : tokens) {
            if (t.isEmpty() || t.chars().anyMatch(Character::isWhitespace)) {
                return null;
            }
        }
        return String.join(" ", tokens);
    }
}
