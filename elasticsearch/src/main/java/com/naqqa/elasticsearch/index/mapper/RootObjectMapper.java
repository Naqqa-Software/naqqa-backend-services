package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.time.DateFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RootObjectMapper extends ObjectMapper {

    private static final List<String> DEFAULT_DATE_FORMATS = List.of("strict_date_optional_time", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd");

    private final boolean dateDetection;
    private final boolean numericDetection;
    private final List<DateFormatter> dynamicDateFormatters;
    private final List<DynamicTemplate> dynamicTemplates;
    private final Map<String, RuntimeFieldMapper> runtimeFields;
    private JsonObject meta;

    public RootObjectMapper(JsonObject mappingNode, Dynamic dynamic, boolean enabled, Map<String, Mapper> properties,
                             boolean dateDetection, boolean numericDetection, List<DateFormatter> dynamicDateFormatters,
                             List<DynamicTemplate> dynamicTemplates, Map<String, RuntimeFieldMapper> runtimeFields, JsonObject meta) {
        super("_doc", "", mappingNode, dynamic == null ? Dynamic.TRUE : dynamic, enabled, properties);
        this.dateDetection = dateDetection;
        this.numericDetection = numericDetection;
        this.dynamicDateFormatters = dynamicDateFormatters;
        this.dynamicTemplates = new ArrayList<>(dynamicTemplates);
        this.runtimeFields = new LinkedHashMap<>(runtimeFields);
        this.meta = meta;
    }

    public RootObjectMapper mergeRoot(RootObjectMapper other) {
        merge(other);
        for (DynamicTemplate t : other.dynamicTemplates) {
            dynamicTemplates.removeIf(existing -> existing.name().equals(t.name()));
            dynamicTemplates.add(t);
        }
        runtimeFields.putAll(other.runtimeFields);
        if (other.meta != null) {
            meta = other.meta;
        }
        return this;
    }

    public static RootObjectMapper parse(JsonObject node, MappingParserContext ctx) {
        Dynamic dynamic = Dynamic.parse(node.getString("dynamic", null), Dynamic.TRUE);
        boolean enabled = FieldMapper.getBool(node, "enabled", true);
        boolean dateDetection = FieldMapper.getBool(node, "date_detection", true);
        boolean numericDetection = FieldMapper.getBool(node, "numeric_detection", false);
        List<String> formats = node.has("dynamic_date_formats") ? readStringList(node) : DEFAULT_DATE_FORMATS;
        List<DateFormatter> formatters = new ArrayList<>();
        for (String f : formats) {
            formatters.add(DateFormatter.forPattern(f));
        }
        Map<String, Mapper> properties = new LinkedHashMap<>();
        JsonObject props = node.getObject("properties");
        if (props != null) {
            for (Map.Entry<String, JsonValue> e : props) {
                properties.put(e.getKey(), ctx.parseMapper(e.getKey(), e.getKey(), e.getValue().asObject(), 1));
            }
        }
        List<DynamicTemplate> templates = new ArrayList<>();
        if (node.has("dynamic_templates")) {
            for (JsonValue v : node.getArray("dynamic_templates")) {
                JsonObject entry = v.asObject();
                for (Map.Entry<String, JsonValue> e : entry) {
                    templates.add(DynamicTemplate.parse(e.getKey(), e.getValue().asObject()));
                }
            }
        }
        Map<String, RuntimeFieldMapper> runtimeFields = new LinkedHashMap<>();
        JsonObject runtimeNode = node.getObject("runtime");
        if (runtimeNode != null) {
            for (Map.Entry<String, JsonValue> e : runtimeNode) {
                runtimeFields.put(e.getKey(), RuntimeFieldMapper.parse(e.getKey(), e.getValue().asObject()));
            }
        }
        JsonObject meta = node.getObject("_meta");
        return new RootObjectMapper(node, dynamic, enabled, properties, dateDetection, numericDetection, formatters, templates, runtimeFields, meta);
    }

    private static List<String> readStringList(JsonObject node) {
        List<String> out = new ArrayList<>();
        for (JsonValue v : node.getArray("dynamic_date_formats")) {
            out.add(v.asString());
        }
        return out;
    }

    public boolean dateDetection() {
        return dateDetection;
    }

    public boolean numericDetection() {
        return numericDetection;
    }

    public List<DateFormatter> dynamicDateFormatters() {
        return dynamicDateFormatters;
    }

    public List<DynamicTemplate> dynamicTemplates() {
        return dynamicTemplates;
    }

    public Map<String, RuntimeFieldMapper> runtimeFields() {
        return runtimeFields;
    }

    public JsonObject meta() {
        return meta;
    }

    @Override
    public void toMapping(JsonObject out) {
        super.toMapping(out);
        out.remove("type");
        if (!dynamicTemplates.isEmpty()) {
            com.naqqa.elasticsearch.common.json.JsonArray arr = new com.naqqa.elasticsearch.common.json.JsonArray();
            for (DynamicTemplate t : dynamicTemplates) {
                JsonObject wrapper = new JsonObject();
                arr.add(wrapper);
            }
            out.put("dynamic_templates", arr);
        }
        if (!runtimeFields.isEmpty()) {
            JsonObject rt = new JsonObject();
            for (Map.Entry<String, RuntimeFieldMapper> e : runtimeFields.entrySet()) {
                JsonObject sub = new JsonObject();
                e.getValue().toMapping(sub);
                rt.put(e.getKey(), sub);
            }
            out.put("runtime", rt);
        }
        if (meta != null) {
            out.put("_meta", meta);
        }
    }
}
