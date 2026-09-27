package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class FieldMapper implements Mapper {

    protected final String simpleName;
    protected final String fullPath;
    protected final JsonObject mappingNode;
    protected final List<String> copyTo;
    protected final Map<String, FieldMapper> multiFields = new LinkedHashMap<>();

    protected FieldMapper(String simpleName, String fullPath, JsonObject mappingNode) {
        this.simpleName = simpleName;
        this.fullPath = fullPath;
        this.mappingNode = mappingNode;
        this.copyTo = readCopyTo(mappingNode);
    }

    @Override
    public String name() {
        return simpleName;
    }

    @Override
    public String fullPath() {
        return fullPath;
    }

    protected abstract void parseCreateField(ParseContext context, Object value);

    protected Object nullValue() {
        return null;
    }

    protected boolean ignoreMalformed() {
        return false;
    }

    protected boolean parsesArrayAsSingleValue() {
        return false;
    }

    public final void parse(ParseContext context, Object value) {
        if (value == null) {
            Object nv = nullValue();
            if (nv != null) {
                doParseAndFanOut(context, nv);
            }
            return;
        }
        doParseAndFanOut(context, value);
    }

    private void doParseAndFanOut(ParseContext context, Object value) {
        try {
            parseCreateField(context, value);
        } catch (RuntimeException e) {
            if (ignoreMalformed()) {
                context.addIgnoredField(fullPath);
                return;
            }
            throw e;
        }
        for (FieldMapper mf : multiFields.values()) {
            mf.parse(context, value);
        }
        for (String target : copyTo) {
            context.addCopyTo(target, value);
        }
    }

    public Map<String, FieldMapper> multiFields() {
        return multiFields;
    }

    public FieldMapper getMultiField(String name) {
        return multiFields.get(name);
    }

    protected Set<String> updatableParameters() {
        return Set.of("meta");
    }

    public FieldMapper merge(FieldMapper other) {
        if (!typeName().equals(other.typeName())) {
            throw new IllegalArgumentException("mapper [" + fullPath + "] cannot be changed from type [" + typeName() + "] to [" + other.typeName() + "]");
        }
        Set<String> updatable = updatableParameters();
        for (String key : mappingNode.keySet()) {
            if (key.equals("properties") || key.equals("fields") || updatable.contains(key)) {
                continue;
            }
            Object a = mappingNode.has(key) ? mappingNode.get(key).toJava() : null;
            Object b = other.mappingNode.has(key) ? other.mappingNode.get(key).toJava() : null;
            if (!Objects.equals(a, b)) {
                throw new IllegalArgumentException("Mapper for [" + fullPath + "] conflicts with existing mapper:\n\tCannot update parameter [" + key + "] from [" + a + "] to [" + b + "]");
            }
        }
        for (String key : other.mappingNode.keySet()) {
            if (key.equals("properties") || key.equals("fields") || updatable.contains(key) || mappingNode.has(key)) {
                continue;
            }
            Object b = other.mappingNode.get(key).toJava();
            if (b != null) {
                throw new IllegalArgumentException("Mapper for [" + fullPath + "] conflicts with existing mapper:\n\tCannot update parameter [" + key + "] from [null] to [" + b + "]");
            }
        }
        Map<String, FieldMapper> mergedMultiFields = new LinkedHashMap<>(this.multiFields);
        for (Map.Entry<String, FieldMapper> e : other.multiFields.entrySet()) {
            FieldMapper existing = mergedMultiFields.get(e.getKey());
            mergedMultiFields.put(e.getKey(), existing == null ? e.getValue() : existing.merge(e.getValue()));
        }
        other.multiFields.clear();
        other.multiFields.putAll(mergedMultiFields);
        return other;
    }

    @Override
    public void toMapping(JsonObject out) {
        for (Map.Entry<String, JsonValue> e : mappingNode) {
            if (e.getKey().equals("fields")) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        if (!out.has("type")) {
            out.put("type", typeName());
        }
        if (!multiFields.isEmpty()) {
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, FieldMapper> e : multiFields.entrySet()) {
                JsonObject sub = new JsonObject();
                e.getValue().toMapping(sub);
                fields.put(e.getKey(), sub);
            }
            out.put("fields", fields);
        }
    }

    protected static boolean getBool(JsonObject node, String key, boolean def) {
        return node.getBoolean(key, def);
    }

    protected static List<String> readCopyTo(JsonObject node) {
        JsonValue v = node.get("copy_to");
        if (v == null || v.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (v.isArray()) {
            for (JsonValue e : v.asArray()) {
                out.add(e.asString());
            }
        } else {
            out.add(v.asString());
        }
        return out;
    }

    protected static Map<String, FieldMapper> parseMultiFields(String parentFullPath, JsonObject node, MappingParserContext ctx, int depth) {
        Map<String, FieldMapper> out = new LinkedHashMap<>();
        JsonObject fields = node.getObject("fields");
        if (fields != null) {
            for (Map.Entry<String, JsonValue> e : fields) {
                String mfName = e.getKey();
                JsonObject mfNode = e.getValue().asObject();
                String mfFullPath = parentFullPath + "." + mfName;
                out.put(mfName, ctx.parseField(mfName, mfFullPath, mfNode, depth));
            }
        }
        return out;
    }

    protected static String stringValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        return String.valueOf(value);
    }
}
