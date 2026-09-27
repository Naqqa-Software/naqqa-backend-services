package com.naqqa.elasticsearch.common.logging;

import com.naqqa.elasticsearch.common.util.concurrent.ThreadContext;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DeprecationLogger {

    private final ESLogger logger;
    private final ThreadContext threadContext;

    public DeprecationLogger(ThreadContext threadContext) {
        this.logger = ESLogger.getLogger("deprecation");
        this.threadContext = threadContext;
    }

    public void deprecate(String key, String msg, Object... params) {
        String formatted = LoggerMessageFormat.format(msg, params);
        logger.warn(formatted);
        if (threadContext != null) {
            threadContext.addResponseHeader("Warning", formatWarningHeader(formatted));
        }
    }

    public void critical(String key, String msg, Object... params) {
        deprecate(key, msg, params);
    }

    private static String formatWarningHeader(String message) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "299 Elasticsearch-Compatible-Server \"" + escaped + "\" \""
            + DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ROOT).format(Instant.now().atZone(java.time.ZoneOffset.UTC)) + "\"";
    }
}
