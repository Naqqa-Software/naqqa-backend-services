package com.naqqa.elasticsearch.http;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class DefaultRestErrorRenderer implements RestErrorRenderer {

    @Override
    public int statusFor(Throwable error) {
        if (error instanceof RestStatusProvider provider) {
            return provider.restStatus();
        }
        if (error instanceof IllegalArgumentException || error instanceof NumberFormatException) {
            return 400;
        }
        if (error instanceof NoSuchElementException) {
            return 404;
        }
        if (error instanceof IllegalStateException) {
            return 409;
        }
        if (error instanceof UnsupportedOperationException) {
            return 501;
        }
        return 500;
    }

    @Override
    public Map<String, Object> render(Throwable error, boolean errorTrace) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", buildErrorObject(error, errorTrace));
        body.put("status", statusFor(error));
        return body;
    }

    private Map<String, Object> buildErrorObject(Throwable error, boolean errorTrace) {
        Throwable deepest = deepestCause(error);
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("root_cause", List.of(causeSummary(deepest)));
        object.put("type", typeName(error));
        object.put("reason", reason(error));
        if (error.getCause() != null && error.getCause() != error) {
            object.put("caused_by", buildErrorObject(error.getCause(), errorTrace));
        }
        if (errorTrace) {
            object.put("stack_trace", stackTraceOf(error));
        }
        return object;
    }

    private Map<String, Object> causeSummary(Throwable error) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", typeName(error));
        summary.put("reason", reason(error));
        return summary;
    }

    private Throwable deepestCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String reason(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private String typeName(Throwable error) {
        String simple = error.getClass().getSimpleName();
        if (simple.isEmpty()) {
            simple = error.getClass().getName();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        String snake = sb.toString();
        return snake.endsWith("_exception") || snake.endsWith("_error") ? snake : snake + "_exception";
    }

    private String stackTraceOf(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
