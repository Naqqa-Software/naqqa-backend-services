package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.common.time.DateFormatter;

final class DynamicFieldsBuilder {

    private DynamicFieldsBuilder() {
    }

    record Detection(String dynamicType, String dateFormat) {
    }

    static Detection detect(Object value, RootObjectMapper root) {
        if (value instanceof Boolean) {
            return new Detection("boolean", null);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return new Detection("long", null);
        }
        if (value instanceof Double || value instanceof Float) {
            return new Detection("double", null);
        }
        if (value instanceof String s) {
            if (root.dateDetection()) {
                for (DateFormatter f : root.dynamicDateFormatters()) {
                    try {
                        f.parse(s);
                        return new Detection("date", f.pattern());
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if (root.numericDetection()) {
                try {
                    Long.parseLong(s);
                    return new Detection("long", null);
                } catch (NumberFormatException ignored) {
                }
                try {
                    Double.parseDouble(s);
                    return new Detection("double", null);
                } catch (NumberFormatException ignored) {
                }
            }
            return new Detection("string", null);
        }
        return new Detection("string", null);
    }

    static JsonObject buildDefaultMapping(String dynamicType, String dateFormat) {
        JsonObject node = new JsonObject();
        switch (dynamicType) {
            case "string" -> {
                node.put("type", "text");
                JsonObject fields = new JsonObject();
                JsonObject kw = new JsonObject();
                kw.put("type", "keyword");
                kw.put("ignore_above", 256);
                fields.put("keyword", kw);
                node.put("fields", fields);
            }
            case "long" -> node.put("type", "long");
            case "double" -> node.put("type", "float");
            case "boolean" -> node.put("type", "boolean");
            case "date" -> {
                node.put("type", "date");
                if (dateFormat != null) {
                    node.put("format", dateFormat);
                }
            }
            case "binary" -> node.put("type", "binary");
            default -> node.put("type", "keyword");
        }
        return node;
    }
}
