package com.naqqa.elasticsearch.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptException extends RuntimeException {

    public record Position(int offset, int start, int end) {
    }

    private final List<String> scriptStack;
    private final String script;
    private final String lang;
    private final Position position;
    private final Map<String, List<String>> metadata = new LinkedHashMap<>();

    public ScriptException(String message, Throwable cause, List<String> scriptStack, String script, String lang) {
        this(message, cause, scriptStack, script, lang, null);
    }

    public ScriptException(String message, Throwable cause, List<String> scriptStack, String script, String lang, Position position) {
        super(message, cause);
        this.scriptStack = scriptStack == null ? List.of() : List.copyOf(scriptStack);
        this.script = script;
        this.lang = lang;
        this.position = position;
    }

    public List<String> getScriptStack() {
        return scriptStack;
    }

    public String getScript() {
        return script;
    }

    public String getLang() {
        return lang;
    }

    public Position getPosition() {
        return position;
    }

    public ScriptException addMetadata(String key, String value) {
        metadata.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        return this;
    }

    public Map<String, List<String>> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public int status() {
        return 400;
    }

    public String type() {
        return "script_exception";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type());
        m.put("reason", getMessage());
        for (Map.Entry<String, List<String>> e : metadata.entrySet()) {
            m.put(e.getKey(), e.getValue().size() == 1 ? e.getValue().get(0) : e.getValue());
        }
        m.put("script_stack", scriptStack);
        m.put("script", script);
        m.put("lang", lang);
        if (position != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("offset", position.offset());
            p.put("start", position.start());
            p.put("end", position.end());
            m.put("position", p);
        }
        Throwable cause = getCause();
        if (cause != null) {
            m.put("caused_by", causeMap(cause));
        }
        return m;
    }

    private static Map<String, Object> causeMap(Throwable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", typeName(t));
        m.put("reason", t.getMessage());
        if (t.getCause() != null && t.getCause() != t) {
            m.put("caused_by", causeMap(t.getCause()));
        }
        return m;
    }

    public static String typeName(Throwable t) {
        if (t instanceof ScriptException se) {
            return se.type();
        }
        if (t instanceof ScriptCircuitBreakingException cb) {
            return cb.type();
        }
        String simple = t.getClass().getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
