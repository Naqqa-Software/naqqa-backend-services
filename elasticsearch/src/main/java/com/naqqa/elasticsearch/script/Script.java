package com.naqqa.elasticsearch.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Script {

    public static final String DEFAULT_SCRIPT_LANG = "painless";
    public static final String DEFAULT_TEMPLATE_LANG = "mustache";
    public static final String CONTENT_TYPE_OPTION = "content_type";

    private final ScriptType type;
    private final String lang;
    private final String idOrCode;
    private final Map<String, String> options;
    private final Map<String, Object> params;

    public Script(String source) {
        this(ScriptType.INLINE, DEFAULT_SCRIPT_LANG, source, Map.of(), Map.of());
    }

    public Script(ScriptType type, String lang, String idOrCode, Map<String, String> options, Map<String, Object> params) {
        this.type = Objects.requireNonNull(type, "type");
        if (idOrCode == null) {
            throw new IllegalArgumentException(type == ScriptType.INLINE ? "must specify <source> for an inline script" : "must specify <id> for a stored script");
        }
        if (type == ScriptType.STORED && lang != null) {
            throw new IllegalArgumentException("lang cannot be specified for stored scripts");
        }
        this.lang = type == ScriptType.INLINE ? (lang == null ? DEFAULT_SCRIPT_LANG : lang) : null;
        this.idOrCode = idOrCode;
        this.options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        this.params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public static Script inline(String source) {
        return new Script(source);
    }

    public static Script inline(String lang, String source, Map<String, Object> params) {
        return new Script(ScriptType.INLINE, lang, source, Map.of(), params);
    }

    public static Script inline(String source, Map<String, Object> params) {
        return new Script(ScriptType.INLINE, DEFAULT_SCRIPT_LANG, source, Map.of(), params);
    }

    public static Script stored(String id, Map<String, Object> params) {
        return new Script(ScriptType.STORED, null, id, Map.of(), params);
    }

    public static Script parse(Object value) {
        return parse(value, DEFAULT_SCRIPT_LANG);
    }

    @SuppressWarnings("unchecked")
    public static Script parse(Object value, String defaultLang) {
        if (value instanceof Script s) {
            return s;
        }
        if (value instanceof CharSequence cs) {
            return new Script(ScriptType.INLINE, defaultLang, cs.toString(), Map.of(), Map.of());
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("[script] must be a string or an object but was [" + value + "]");
        }
        String source = null;
        String id = null;
        String lang = null;
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (key) {
                case "source", "inline" -> {
                    if (source != null) {
                        throw new IllegalArgumentException("[script] duplicate source");
                    }
                    if (v instanceof Map<?, ?> || v instanceof Iterable<?>) {
                        source = ScriptJson.toJson(v);
                        if (!options.containsKey(CONTENT_TYPE_OPTION)) {
                            options.put(CONTENT_TYPE_OPTION, "application/json");
                        }
                    } else if (v != null) {
                        source = v.toString();
                    } else {
                        throw new IllegalArgumentException("[script] source must not be null");
                    }
                }
                case "id" -> id = v == null ? null : v.toString();
                case "lang" -> lang = v == null ? null : v.toString();
                case "params" -> {
                    if (v != null) {
                        if (!(v instanceof Map<?, ?> pm)) {
                            throw new IllegalArgumentException("[script] params must be an object");
                        }
                        for (Map.Entry<?, ?> pe : pm.entrySet()) {
                            params.put(String.valueOf(pe.getKey()), pe.getValue());
                        }
                    }
                }
                case "options" -> {
                    if (v instanceof Map<?, ?> om) {
                        for (Map.Entry<?, ?> oe : om.entrySet()) {
                            options.put(String.valueOf(oe.getKey()), String.valueOf(oe.getValue()));
                        }
                    } else if (v != null) {
                        throw new IllegalArgumentException("[script] options must be an object");
                    }
                }
                default -> throw new IllegalArgumentException("[script] unknown field [" + key + "]");
            }
        }
        if (source != null && id != null) {
            throw new IllegalArgumentException("[script] only one of [id] or [source] may be specified");
        }
        if (source == null && id == null) {
            throw new IllegalArgumentException("must specify either [source] for an inline script or [id] for a stored script");
        }
        if (id != null) {
            if (lang != null) {
                throw new IllegalArgumentException("lang cannot be specified for stored scripts");
            }
            if (!options.isEmpty()) {
                throw new IllegalArgumentException("field [options] of script [" + id + "] must not be specified for stored scripts");
            }
            return new Script(ScriptType.STORED, null, id, Map.of(), params);
        }
        return new Script(ScriptType.INLINE, lang == null ? defaultLang : lang, source, options, params);
    }

    public ScriptType getType() {
        return type;
    }

    public String getLang() {
        return lang;
    }

    public String getIdOrCode() {
        return idOrCode;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public Script withParams(Map<String, Object> newParams) {
        return new Script(type, lang, idOrCode, options, newParams);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (type == ScriptType.INLINE) {
            m.put("source", idOrCode);
            m.put("lang", lang);
            if (!options.isEmpty()) {
                m.put("options", options);
            }
        } else {
            m.put("id", idOrCode);
        }
        if (!params.isEmpty()) {
            m.put("params", params);
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Script s)) {
            return false;
        }
        return type == s.type && Objects.equals(lang, s.lang) && idOrCode.equals(s.idOrCode) && options.equals(s.options) && params.equals(s.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, lang, idOrCode, options, params);
    }

    @Override
    public String toString() {
        return "Script{" + toMap() + "}";
    }
}
