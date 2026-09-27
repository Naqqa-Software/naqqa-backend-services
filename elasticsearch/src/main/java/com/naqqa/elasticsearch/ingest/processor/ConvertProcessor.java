package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.AbstractProcessor;
import com.naqqa.elasticsearch.ingest.ConfigurationUtils;
import com.naqqa.elasticsearch.ingest.IngestDocument;
import com.naqqa.elasticsearch.ingest.Processor;
import com.naqqa.elasticsearch.ingest.ProcessorRegistry;

import java.net.InetAddress;
import java.util.Map;

public final class ConvertProcessor extends AbstractProcessor {

    public static final String TYPE = "convert";

    private final String field;
    private final String targetField;
    private final String type;
    private final boolean ignoreMissing;

    public ConvertProcessor(String tag, String description, String field, String targetField, String type, boolean ignoreMissing) {
        super(TYPE, tag, description);
        this.field = field;
        this.targetField = targetField;
        this.type = type;
        this.ignoreMissing = ignoreMissing;
    }

    @Override
    public IngestDocument execute(IngestDocument document) {
        String resolvedField = document.renderTemplate(field);
        if (!document.hasField(resolvedField)) {
            if (ignoreMissing) {
                return document;
            }
            throw new IllegalArgumentException("field [" + resolvedField + "] not present as part of path [" + resolvedField + "]");
        }
        Object value = document.getFieldValue(resolvedField, Object.class);
        Object converted = value instanceof java.util.List<?> list ? convertList(list) : convertSingle(value);
        String resolvedTarget = document.renderTemplate(targetField);
        document.setFieldValue(resolvedTarget, converted);
        return document;
    }

    private Object convertList(java.util.List<?> list) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        for (Object o : list) {
            result.add(convertSingle(o));
        }
        return result;
    }

    private Object convertSingle(Object value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case "integer" -> value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString().trim());
            case "long" -> value instanceof Number n ? n.longValue() : Long.parseLong(value.toString().trim());
            case "float" -> value instanceof Number n ? n.floatValue() : Float.parseFloat(value.toString().trim());
            case "double" -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
            case "boolean" -> convertBoolean(value);
            case "ip" -> convertIp(value);
            case "string" -> value.toString();
            case "auto" -> autoConvert(value);
            default -> throw new IllegalArgumentException("unsupported convert type [" + type + "]");
        };
    }

    private static Boolean convertBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String s = value.toString().trim();
        if ("true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("[" + s + "] is not a boolean value, cannot convert to boolean");
    }

    private static String convertIp(Object value) {
        String s = value.toString().trim();
        try {
            InetAddress.getByName(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("[" + s + "] is not a valid IP string literal");
        }
        return s;
    }

    private static Object autoConvert(Object value) {
        if (!(value instanceof String s)) {
            return value;
        }
        String trimmed = s.trim();
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException ignored) {
        }
        return s;
    }

    public static final class Factory implements Processor.Factory {
        @Override
        public Processor create(ProcessorRegistry registry, String tag, String description, Map<String, Object> config) {
            String field = ConfigurationUtils.readStringProperty(TYPE, tag, config, "field");
            String type = ConfigurationUtils.readStringProperty(TYPE, tag, config, "type");
            String targetField = ConfigurationUtils.readStringProperty(TYPE, tag, config, "target_field", field);
            boolean ignoreMissing = ConfigurationUtils.readBooleanProperty(TYPE, tag, config, "ignore_missing", false);
            return new ConvertProcessor(tag, description, field, targetField, type, ignoreMissing);
        }
    }
}
