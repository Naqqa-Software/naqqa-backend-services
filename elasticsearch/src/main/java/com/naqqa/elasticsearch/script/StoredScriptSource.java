package com.naqqa.elasticsearch.script;

import java.util.LinkedHashMap;
import java.util.Map;

public record StoredScriptSource(String lang, String source, Map<String, String> options) {

    public StoredScriptSource {
        if (lang == null || lang.isEmpty()) {
            throw new IllegalArgumentException("must specify lang for stored script");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("must specify source for stored script");
        }
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static StoredScriptSource parse(Map<String, Object> body) {
        Object inner = body.get("script");
        if (inner == null) {
            if (body.containsKey("template")) {
                Object tpl = body.get("template");
                return new StoredScriptSource(Script.DEFAULT_TEMPLATE_LANG, tpl instanceof String s ? s : ScriptJson.toJson(tpl), Map.of(Script.CONTENT_TYPE_OPTION, "application/json"));
            }
            throw new IllegalArgumentException("must specify [script] for stored script");
        }
        if (!(inner instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("[script] must be an object for stored script");
        }
        String lang = null;
        String source = null;
        Map<String, String> options = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (key) {
                case "lang" -> lang = v == null ? null : v.toString();
                case "source", "code" -> {
                    if (v instanceof Map<?, ?> || v instanceof Iterable<?>) {
                        source = ScriptJson.toJson(v);
                        options.putIfAbsent(Script.CONTENT_TYPE_OPTION, "application/json");
                    } else {
                        source = v == null ? null : v.toString();
                    }
                }
                case "options" -> {
                    if (v instanceof Map<?, ?> om) {
                        for (Map.Entry<?, ?> oe : om.entrySet()) {
                            options.put(String.valueOf(oe.getKey()), String.valueOf(oe.getValue()));
                        }
                    }
                }
                default -> throw new IllegalArgumentException("unexpected field [" + key + "] for stored script");
            }
        }
        return new StoredScriptSource(lang, source, options);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lang", lang);
        m.put("source", source);
        if (!options.isEmpty()) {
            m.put("options", options);
        }
        return m;
    }
}
