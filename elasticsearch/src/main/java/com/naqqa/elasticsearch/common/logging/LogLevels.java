package com.naqqa.elasticsearch.common.logging;

import java.util.Locale;
import java.util.logging.Level;

public final class LogLevels {

    private LogLevels() {
    }

    public static Level parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("log level must not be null");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ERROR" -> Level.SEVERE;
            case "WARN", "WARNING" -> Level.WARNING;
            case "INFO" -> Level.INFO;
            case "DEBUG" -> Level.FINE;
            case "TRACE" -> Level.FINEST;
            case "OFF" -> Level.OFF;
            case "ALL" -> Level.ALL;
            default -> throw new IllegalArgumentException("unknown log level [" + value + "]");
        };
    }

    public static String name(Level level) {
        int value = level.intValue();
        if (value == Level.OFF.intValue()) {
            return "OFF";
        }
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= Level.INFO.intValue()) {
            return "INFO";
        }
        if (value >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }
}
