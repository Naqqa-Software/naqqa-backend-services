package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

public class ObjectMapper implements Mapper {

    protected final String simpleName;
    protected final String fullPath;
    protected final JsonObject mappingNode;
    protected Dynamic dynamic;
    protected final boolean enabled;
    protected final Map<String, Mapper> properties;

    public ObjectMapper(String simpleName, String fullPath, JsonObject mappingNode, Dynamic dynamic, boolean enabled, Map<String, Mapper> properties) {
        this.simpleName = simpleName;
        this.fullPath = fullPath;
        this.mappingNode = mappingNode;
        this.dynamic = dynamic;
        this.enabled = enabled;
        this.properties = new LinkedHashMap<>(properties);
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        String type = node.getString("type", "object");
        boolean nested = type.equals("nested");
        Dynamic dynamic = Dynamic.parse(node.getString("dynamic", null), null);
        boolean enabled = FieldMapper.getBool(node, "enabled", true);
        Map<String, Mapper> properties = new LinkedHashMap<>();
        JsonObject props = node.getObject("properties");
        if (props != null) {
            for (Map.Entry<String, JsonValue> e : props) {
                String childName = e.getKey();
                JsonObject childNode = e.getValue().asObject();
                String childFullPath = fullPath.isEmpty() ? childName : fullPath + "." + childName;
                properties.put(childName, ctx.parseMapper(childName, childFullPath, childNode, depth + 1));
            }
        }
        if (nested) {
            boolean includeInParent = FieldMapper.getBool(node, "include_in_parent", false);
            boolean includeInRoot = FieldMapper.getBool(node, "include_in_root", false);
            return new NestedObjectMapper(name, fullPath, node, dynamic, enabled, properties, includeInParent, includeInRoot);
        }
        return new ObjectMapper(name, fullPath, node, dynamic, enabled, properties);
    }

    @Override
    public String name() {
        return simpleName;
    }

    @Override
    public String fullPath() {
        return fullPath;
    }

    @Override
    public String typeName() {
        return "object";
    }

    public Dynamic dynamic() {
        return dynamic;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Mapper> properties() {
        return properties;
    }

    public Mapper getMapper(String name) {
        return properties.get(name);
    }

    public void putMapper(String name, Mapper mapper) {
        properties.put(name, mapper);
    }

    public Mapper merge(Mapper otherMapper) {
        if (!(otherMapper instanceof ObjectMapper other) || !typeName().equals(other.typeName())) {
            throw new IllegalArgumentException("object mapper [" + fullPath + "] cannot be changed from type [" + typeName() + "] to [" + otherMapper.typeName() + "]");
        }
        if (enabled != other.enabled) {
            throw new IllegalArgumentException("the [enabled] parameter can't be updated for the object mapper [" + fullPath + "]");
        }
        if (other.dynamic != null) {
            this.dynamic = other.dynamic;
        }
        for (Map.Entry<String, Mapper> e : other.properties.entrySet()) {
            Mapper existing = properties.get(e.getKey());
            if (existing == null) {
                properties.put(e.getKey(), e.getValue());
            } else if (existing instanceof FieldMapper existingField && e.getValue() instanceof FieldMapper newField) {
                properties.put(e.getKey(), existingField.merge(newField));
            } else if (existing instanceof ObjectMapper existingObj) {
                properties.put(e.getKey(), existingObj.merge(e.getValue()));
            } else {
                throw new IllegalArgumentException("mapper [" + e.getKey() + "] cannot be changed from type [" + existing.typeName() + "] to [" + e.getValue().typeName() + "]");
            }
        }
        return this;
    }

    @Override
    public void toMapping(JsonObject out) {
        out.put("type", typeName());
        if (dynamic != null) {
            out.put("dynamic", dynamic.toValue());
        }
        if (!enabled) {
            out.put("enabled", false);
        }
        JsonObject props = new JsonObject();
        for (Map.Entry<String, Mapper> e : properties.entrySet()) {
            JsonObject sub = new JsonObject();
            e.getValue().toMapping(sub);
            props.put(e.getKey(), sub);
        }
        out.put("properties", props);
    }
}
