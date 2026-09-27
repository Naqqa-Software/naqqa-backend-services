package com.naqqa.elasticsearch.common.json;

import java.io.StringWriter;
import java.io.Writer;

public final class JsonWriter {

    private JsonWriter() {
    }

    public static String toJson(Object value, boolean pretty) {
        StringWriter sw = new StringWriter();
        write(sw, value, pretty);
        return sw.toString();
    }

    public static void write(Writer out, Object value, boolean pretty) {
        JsonGenerator gen = new JsonGenerator(out, pretty);
        Object javaValue = value instanceof JsonValue jv ? jv.toJava() : value;
        gen.writeValue(javaValue);
        gen.flush();
    }

    public static byte[] toJsonBytes(Object value, boolean pretty) {
        return toJson(value, pretty).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
