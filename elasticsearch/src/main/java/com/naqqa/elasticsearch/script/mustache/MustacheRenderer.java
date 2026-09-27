package com.naqqa.elasticsearch.script.mustache;

import com.naqqa.elasticsearch.script.ScriptJson;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MustacheRenderer {

    private static final Pattern PARAM_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(?:'([^']*)'|\"([^\"]*)\")");

    private final Map<String, String> partials;
    private final boolean jsonEscape;

    public MustacheRenderer(Map<String, String> partials, boolean jsonEscape) {
        this.partials = partials == null ? Map.of() : partials;
        this.jsonEscape = jsonEscape;
    }

    public static String render(String template, Object context) {
        return new MustacheRenderer(Map.of(), true).renderTemplate(template, context);
    }

    public String renderTemplate(String template, Object context) {
        List<MustacheNode.Node> nodes = MustacheParser.parse(template);
        StringBuilder out = new StringBuilder();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(context);
        renderNodes(nodes, stack, out);
        return out.toString();
    }

    private void renderNodes(List<MustacheNode.Node> nodes, Deque<Object> stack, StringBuilder out) {
        for (MustacheNode.Node node : nodes) {
            switch (node) {
                case MustacheNode.Node.Text t -> out.append(t.content());
                case MustacheNode.Node.Variable v -> {
                    Object value = resolve(v.name(), stack);
                    String text = value == null ? "" : String.valueOf(value);
                    out.append(v.escape() ? escape(text) : text);
                }
                case MustacheNode.Node.Partial p -> {
                    String partialTemplate = partials.get(p.name());
                    if (partialTemplate != null) {
                        renderNodes(MustacheParser.parse(partialTemplate), stack, out);
                    }
                }
                case MustacheNode.Node.Section s -> renderSection(s, stack, out);
            }
        }
    }

    private void renderSection(MustacheNode.Node.Section s, Deque<Object> stack, StringBuilder out) {
        String key = sectionKey(s.name());
        if (key.equals("toJson")) {
            Object value = resolve(s.rawBody(), stack);
            out.append(ScriptJson.toJson(value));
            return;
        }
        if (key.equals("join")) {
            Map<String, String> params = parseParams(s.name());
            String delimiter = params.getOrDefault("delimiter", ",");
            Object value = resolve(s.rawBody(), stack);
            out.append(joinValues(value, delimiter));
            return;
        }
        if (key.equals("url")) {
            Object value = resolve(s.rawBody(), stack);
            out.append(URLEncoder.encode(value == null ? "" : String.valueOf(value), StandardCharsets.UTF_8));
            return;
        }
        Object value = resolve(s.name(), stack);
        boolean truthy = isTruthy(value);
        if (s.inverted()) {
            if (!truthy) {
                renderNodes(s.children(), stack, out);
            }
            return;
        }
        if (!truthy) {
            return;
        }
        if (value instanceof Collection<?> coll) {
            for (Object item : coll) {
                stack.push(item);
                renderNodes(s.children(), stack, out);
                stack.pop();
            }
        } else if (value instanceof Object[] arr) {
            for (Object item : arr) {
                stack.push(item);
                renderNodes(s.children(), stack, out);
                stack.pop();
            }
        } else {
            stack.push(value);
            renderNodes(s.children(), stack, out);
            stack.pop();
        }
    }

    private String joinValues(Object value, String delimiter) {
        StringBuilder sb = new StringBuilder();
        if (value instanceof Collection<?> coll) {
            boolean first = true;
            for (Object item : coll) {
                if (!first) {
                    sb.append(delimiter);
                }
                first = false;
                sb.append(item == null ? "" : item.toString());
            }
        } else if (value != null) {
            sb.append(value);
        }
        return sb.toString();
    }

    private Map<String, String> parseParams(String sectionName) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PARAM_PATTERN.matcher(sectionName);
        while (m.find()) {
            String v = m.group(2) != null ? m.group(2) : m.group(3);
            params.put(m.group(1), v);
        }
        return params;
    }

    private String sectionKey(String name) {
        int space = name.indexOf(' ');
        return (space < 0 ? name : name.substring(0, space)).trim();
    }

    private boolean isTruthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof Collection<?> coll) {
            return !coll.isEmpty();
        }
        if (value instanceof Object[] arr) {
            return arr.length > 0;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Object resolve(String name, Deque<Object> stack) {
        String trimmed = name.trim();
        if (trimmed.equals(".") || trimmed.isEmpty()) {
            return stack.peek();
        }
        String[] parts = trimmed.split("\\.");
        Object current = null;
        boolean found = false;
        for (Object frame : stack) {
            if (frame instanceof Map<?, ?> map && map.containsKey(parts[0])) {
                current = map.get(parts[0]);
                found = true;
                break;
            }
        }
        if (!found) {
            return null;
        }
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }

    private String escape(String text) {
        if (jsonEscape) {
            return ScriptJson.escape(text);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
