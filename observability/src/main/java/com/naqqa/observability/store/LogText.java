package com.naqqa.observability.store;

import com.naqqa.observability.telemetry.Telemetry.LogRecord;

import java.util.Map;

final class LogText {

    private LogText() {
    }

    static boolean contains(LogRecord log, String needle) {
        if (has(log.message(), needle) || has(log.service(), needle) || has(log.level(), needle) || has(log.traceId(), needle)) {
            return true;
        }
        return mapHas(log.attributes(), needle) || mapHas(log.resource(), needle);
    }

    private static boolean mapHas(Map<String, String> values, String needle) {
        if (values == null) {
            return false;
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (has(e.getValue(), needle) || has(e.getKey(), needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }
}
