package com.naqqa.elasticsearch.common.exception;

import com.naqqa.elasticsearch.common.logging.LoggerMessageFormat;
import com.naqqa.elasticsearch.common.xcontent.ToXContent;
import com.naqqa.elasticsearch.common.xcontent.XContentGenerator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ElasticsearchException extends RuntimeException {

    private final Map<String, List<String>> metadata = new LinkedHashMap<>();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();

    public ElasticsearchException(String message) {
        super(message);
    }

    public ElasticsearchException(String message, Throwable cause) {
        super(message, cause);
    }

    public ElasticsearchException(String msg, Object... args) {
        super(LoggerMessageFormat.format(msg, args));
    }

    public ElasticsearchException(String msg, Throwable cause, Object... args) {
        super(LoggerMessageFormat.format(msg, args), cause);
    }

    public RestStatus status() {
        Throwable cause = getCause();
        if (cause != null && cause != this) {
            return ExceptionsHelper.status(cause);
        }
        return RestStatus.INTERNAL_SERVER_ERROR;
    }

    public ElasticsearchException addMetadata(String key, String... values) {
        metadata.put(key, List.of(values));
        return this;
    }

    public ElasticsearchException addMetadata(String key, List<String> values) {
        metadata.put(key, Collections.unmodifiableList(new ArrayList<>(values)));
        return this;
    }

    public List<String> getMetadata(String key) {
        return metadata.get(key);
    }

    public Map<String, List<String>> getMetadata() {
        return metadata;
    }

    public ElasticsearchException addHeader(String key, String... values) {
        headers.put(key, List.of(values));
        return this;
    }

    public List<String> getHeader(String key) {
        return headers.get(key);
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Throwable[] guessRootCauses() {
        Throwable cause = getCause();
        if (cause != null && cause instanceof ElasticsearchException) {
            return ((ElasticsearchException) cause).guessRootCauses();
        } else if (cause != null) {
            return ExceptionsHelper.guessRootCauses(cause);
        }
        return new Throwable[] { this };
    }

    public String getExceptionName() {
        return getExceptionName(this);
    }

    public static String getExceptionName(Throwable ex) {
        String simpleName = ex.getClass().getSimpleName();
        if (simpleName.startsWith("Elasticsearch")) {
            simpleName = simpleName.substring("Elasticsearch".length());
        }
        return toSnakeCase(simpleName);
    }

    static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 8);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public XContentGenerator toXContent(XContentGenerator generator, ToXContent.Params params) {
        generateThrowableXContent(generator, params, this);
        return generator;
    }

    public static void generateThrowableXContent(XContentGenerator gen, ToXContent.Params params, Throwable t) {
        t = ExceptionsHelper.unwrapCause(t);
        gen.writeStartObject();
        innerToXContent(gen, params, t);
        gen.writeEndObject();
    }

    private static void innerToXContent(XContentGenerator gen, ToXContent.Params params, Throwable t) {
        gen.field("type", getExceptionName(t));
        gen.field("reason", t.getMessage() == null ? t.toString() : t.getMessage());
        if (t instanceof ElasticsearchException ee) {
            for (Map.Entry<String, List<String>> e : ee.getMetadata().entrySet()) {
                String key = e.getKey();
                if (!key.startsWith("es.")) {
                    key = key;
                }
                List<String> values = e.getValue();
                if (values.size() == 1) {
                    gen.field(key, values.get(0));
                } else {
                    gen.field(key, values);
                }
            }
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            gen.writeFieldName("caused_by");
            gen.writeStartObject();
            innerToXContent(gen, params, cause);
            gen.writeEndObject();
        }
        if (params.paramAsBoolean("error_trace", false)) {
            gen.field("stack_trace", ExceptionsHelper.detailedMessage(t));
        }
    }

    public static void generateFailureXContent(XContentGenerator gen, ToXContent.Params params, Exception e, boolean detailed) {
        gen.writeFieldName("error");
        if (!detailed || e == null) {
            gen.writeString(e == null ? "unknown" : ExceptionsHelper.simpleMessage(e));
            return;
        }
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t && t instanceof ElasticsearchException == false) {
            t = t.getCause();
        }
        Throwable[] rootCauses = ExceptionsHelper.guessRootCauses(e);
        gen.writeStartObject();
        gen.writeFieldName("root_cause");
        gen.writeStartArray();
        for (Throwable root : rootCauses) {
            gen.writeStartObject();
            innerToXContent(gen, params, root);
            gen.writeEndObject();
        }
        gen.writeEndArray();
        innerToXContent(gen, params, t);
        gen.writeEndObject();
        gen.field("status", ExceptionsHelper.status(e).getStatus());
    }
}
