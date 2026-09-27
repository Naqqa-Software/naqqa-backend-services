package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeFieldMapper implements Mapper {

    private static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of(
        "keyword", "long", "double", "boolean", "date", "ip", "geo_point", "composite", "lookup");

    private final String name;
    private final String runtimeType;
    private final String scriptSource;
    private final String scriptLang;
    private final Map<String, Object> scriptParams;
    private final String format;
    private final JsonObject mappingNode;

    private RuntimeFieldMapper(String name, String runtimeType, String scriptSource, String scriptLang,
                                Map<String, Object> scriptParams, String format, JsonObject mappingNode) {
        this.name = name;
        this.runtimeType = runtimeType;
        this.scriptSource = scriptSource;
        this.scriptLang = scriptLang;
        this.scriptParams = scriptParams;
        this.format = format;
        this.mappingNode = mappingNode;
    }

    @SuppressWarnings("unchecked")
    public static RuntimeFieldMapper parse(String name, JsonObject node) {
        String type = node.getString("type");
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("runtime field [" + name + "] has unsupported type [" + type + "]");
        }
        String source = null;
        String lang = "painless";
        Map<String, Object> params = new LinkedHashMap<>();
        JsonObject script = node.getObject("script");
        if (script != null) {
            source = script.getString("source", null);
            lang = script.getString("lang", "painless");
            JsonObject p = script.getObject("params");
            if (p != null) {
                params.putAll((Map<String, Object>) p.toJava());
            }
        }
        String format = node.getString("format", null);
        return new RuntimeFieldMapper(name, type, source, lang, params, format, node);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String fullPath() {
        return name;
    }

    @Override
    public String typeName() {
        return "runtime";
    }

    public String runtimeType() {
        return runtimeType;
    }

    public String scriptSource() {
        return scriptSource;
    }

    public String scriptLang() {
        return scriptLang;
    }

    public Map<String, Object> scriptParams() {
        return scriptParams;
    }

    public String format() {
        return format;
    }

    public RuntimeFieldScript compile(ScriptCompiler compiler) {
        if (scriptSource == null) {
            return null;
        }
        return compiler.compile(scriptSource, scriptLang, scriptParams, runtimeType);
    }

    @Override
    public void toMapping(JsonObject out) {
        for (Map.Entry<String, com.naqqa.elasticsearch.common.json.JsonValue> e : mappingNode) {
            out.put(e.getKey(), e.getValue());
        }
    }
}
