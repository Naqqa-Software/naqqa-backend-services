package com.naqqa.elasticsearch.common.exception;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExceptionsHelper {

    private ExceptionsHelper() {
    }

    public static RestStatus status(Throwable t) {
        if (t == null) {
            return RestStatus.INTERNAL_SERVER_ERROR;
        }
        if (t instanceof ElasticsearchException ee) {
            return ee.status();
        }
        if (t instanceof IllegalArgumentException) {
            return RestStatus.BAD_REQUEST;
        }
        if (t instanceof NumberFormatException) {
            return RestStatus.BAD_REQUEST;
        }
        if (t instanceof java.io.EOFException) {
            return RestStatus.BAD_REQUEST;
        }
        if (t instanceof UnsupportedOperationException) {
            return RestStatus.BAD_REQUEST;
        }
        if (t instanceof SecurityException) {
            return RestStatus.FORBIDDEN;
        }
        return RestStatus.INTERNAL_SERVER_ERROR;
    }

    public static Throwable unwrapCause(Throwable t) {
        int counter = 0;
        Throwable result = t;
        while (result instanceof java.lang.reflect.InvocationTargetException || result instanceof java.util.concurrent.ExecutionException) {
            Throwable cause = result.getCause();
            if (cause == null || cause == result) {
                return result;
            }
            if (counter++ > 10) {
                return result;
            }
            result = cause;
        }
        return result;
    }

    public static Throwable[] guessRootCauses(Throwable t) {
        Throwable cause = unwrapCause(t);
        if (cause instanceof ElasticsearchException ee) {
            return ee.guessRootCauses();
        }
        Set<Throwable> seen = new LinkedHashSet<>();
        Throwable current = cause;
        Throwable deepest = cause;
        while (current != null && current.getCause() != null && current.getCause() != current && seen.add(current)) {
            current = current.getCause();
            deepest = current;
        }
        return new Throwable[] { deepest };
    }

    public static String detailedMessage(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    public static String[] stackTrace(Throwable t) {
        String full = detailedMessage(t);
        return full.split("\\r?\\n");
    }

    public static String simpleMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            msg = t.toString();
        }
        return "Elasticsearch exception [type=" + ElasticsearchException.getExceptionName(t) + ", reason=" + msg + "]";
    }

    public static List<Throwable> flatten(Throwable t) {
        List<Throwable> list = new ArrayList<>();
        Throwable current = t;
        while (current != null) {
            list.add(current);
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return list;
    }
}
