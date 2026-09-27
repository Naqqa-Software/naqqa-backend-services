package com.naqqa.elasticsearch.ingest.grok;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Grok {

    private static final Pattern TOKEN = Pattern.compile("%\\{(\\w+)(?::([A-Za-z0-9_.\\-\\[\\]@]+))?(?::(\\w+))?}");
    private static final int MAX_DEPTH = 60;

    private record Capture(int groupIndex, String name, String type) {
    }

    private final Pattern compiled;
    private final List<Capture> captures;

    public Grok(String expression, Map<String, String> patternDefinitions) {
        Map<String, String> defs = new LinkedHashMap<>(GrokPatterns.DEFAULT);
        if (patternDefinitions != null) {
            defs.putAll(patternDefinitions);
        }
        List<Capture> caps = new ArrayList<>();
        int[] groupCounter = {0};
        String regex = resolve(expression, defs, caps, groupCounter, 0);
        this.compiled = Pattern.compile(regex);
        this.captures = caps;
    }

    private static String resolve(String pattern, Map<String, String> defs, List<Capture> captures, int[] groupCounter, int depth) {
        if (depth > MAX_DEPTH) {
            throw new GrokException("grok pattern recursion too deep, possible cyclic pattern definition");
        }
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(pattern, last, m.start());
            String syntax = m.group(1);
            String semantic = m.group(2);
            String type = m.group(3);
            String def = defs.get(syntax);
            if (def == null) {
                throw new GrokException("Unable to find pattern [" + syntax + "] in Grok's pattern dictionary");
            }
            if (semantic != null) {
                int myGroup = ++groupCounter[0];
                String inner = resolve(def, defs, captures, groupCounter, depth + 1);
                sb.append('(').append(inner).append(')');
                captures.add(new Capture(myGroup, semantic, type));
            } else {
                String inner = resolve(def, defs, captures, groupCounter, depth + 1);
                sb.append("(?:").append(inner).append(')');
            }
            last = m.end();
        }
        sb.append(pattern, last, pattern.length());
        return sb.toString();
    }

    public boolean matches(String text) {
        return compiled.matcher(text).find();
    }

    public Map<String, Object> match(String text) {
        Matcher m = compiled.matcher(text);
        if (!m.find()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Capture c : captures) {
            String value;
            try {
                value = m.group(c.groupIndex());
            } catch (IndexOutOfBoundsException e) {
                continue;
            }
            if (value == null) {
                continue;
            }
            result.put(c.name(), convert(value, c.type()));
        }
        return result;
    }

    private static Object convert(String value, String type) {
        if (type == null) {
            return value;
        }
        try {
            return switch (type) {
                case "int" -> Integer.parseInt(value.trim());
                case "long" -> Long.parseLong(value.trim());
                case "float" -> Float.parseFloat(value.trim());
                case "double" -> Double.parseDouble(value.trim());
                case "boolean" -> Boolean.parseBoolean(value.trim());
                default -> value;
            };
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
