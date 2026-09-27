package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonArray;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.util.Map;

public final class DynamicTemplate {

    private final String name;
    private final String match;
    private final String unmatch;
    private final String pathMatch;
    private final String pathUnmatch;
    private final String matchMappingType;
    private final String unmatchMappingType;
    private final boolean matchOnlyText;
    private final JsonObject mapping;

    private DynamicTemplate(String name, String match, String unmatch, String pathMatch, String pathUnmatch,
                             String matchMappingType, String unmatchMappingType, boolean matchOnlyText, JsonObject mapping) {
        this.name = name;
        this.match = match;
        this.unmatch = unmatch;
        this.pathMatch = pathMatch;
        this.pathUnmatch = pathUnmatch;
        this.matchMappingType = matchMappingType;
        this.unmatchMappingType = unmatchMappingType;
        this.matchOnlyText = matchOnlyText;
        this.mapping = mapping;
    }

    public static DynamicTemplate parse(String name, JsonObject node) {
        String match = node.getString("match", null);
        String unmatch = node.getString("unmatch", null);
        String pathMatch = node.getString("path_match", null);
        String pathUnmatch = node.getString("path_unmatch", null);
        String matchMappingType = node.getString("match_mapping_type", null);
        String unmatchMappingType = node.getString("unmatch_mapping_type", null);
        boolean matchOnlyText = "string".equals(matchMappingType) || "text".equals(matchMappingType);
        JsonObject mapping = node.getObject("mapping");
        if (mapping == null) {
            throw new IllegalArgumentException("dynamic template [" + name + "] is missing required [mapping] field");
        }
        return new DynamicTemplate(name, match, unmatch, pathMatch, pathUnmatch, matchMappingType, unmatchMappingType, matchOnlyText, mapping);
    }

    public String name() {
        return name;
    }

    public boolean matches(String path, String fieldName, String dynamicType) {
        if (matchMappingType != null && !matchMappingType.equals("*") && !matchMappingType.equals(dynamicType)) {
            return false;
        }
        if (unmatchMappingType != null && unmatchMappingType.equals(dynamicType)) {
            return false;
        }
        if (match != null && !Globs.match(match, fieldName)) {
            return false;
        }
        if (unmatch != null && Globs.match(unmatch, fieldName)) {
            return false;
        }
        if (pathMatch != null && !Globs.match(pathMatch, path)) {
            return false;
        }
        if (pathUnmatch != null && Globs.match(pathUnmatch, path)) {
            return false;
        }
        return true;
    }

    public JsonObject buildMapping(String fieldName, String dynamicType) {
        return (JsonObject) substitute(mapping, fieldName, dynamicType);
    }

    private static JsonValue substitute(JsonValue value, String fieldName, String dynamicType) {
        if (value.isObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonValue> e : value.asObject()) {
                out.put(e.getKey(), substitute(e.getValue(), fieldName, dynamicType));
            }
            return out;
        }
        if (value.isArray()) {
            JsonArray out = new JsonArray();
            for (JsonValue v : value.asArray()) {
                out.add(substitute(v, fieldName, dynamicType));
            }
            return out;
        }
        if (value.isString()) {
            String s = value.asString();
            s = s.replace("{name}", fieldName).replace("{dynamic_type}", dynamicType).replace("{dynamicType}", dynamicType);
            return JsonValue.of(s);
        }
        return value;
    }
}
