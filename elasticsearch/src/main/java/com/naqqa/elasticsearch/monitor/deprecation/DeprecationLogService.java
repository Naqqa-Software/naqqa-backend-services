package com.naqqa.elasticsearch.monitor.deprecation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DeprecationLogService {

    private final String warnAgent;
    private final Map<String, DeprecationWarning> warningsByKey = new ConcurrentHashMap<>();
    private final List<String> keyOrder = new CopyOnWriteArrayList<>();

    public DeprecationLogService(String warnAgent) {
        this.warnAgent = warnAgent;
    }

    public boolean warn(String key, String message) {
        return warn(key, message, Instant.now());
    }

    public boolean warn(String key, String message, Instant timestamp) {
        DeprecationWarning existing = warningsByKey.putIfAbsent(key, new DeprecationWarning(key, message, timestamp));
        if (existing == null) {
            keyOrder.add(key);
            return true;
        }
        return false;
    }

    public List<DeprecationWarning> warnings() {
        List<DeprecationWarning> result = new ArrayList<>();
        for (String key : keyOrder) {
            DeprecationWarning warning = warningsByKey.get(key);
            if (warning != null) {
                result.add(warning);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<String> warningHeaders() {
        List<String> headers = new ArrayList<>();
        for (DeprecationWarning warning : warnings()) {
            headers.add(formatWarningHeader(warnAgent, warning.message(), warning.timestamp()));
        }
        return headers;
    }

    public void clear() {
        warningsByKey.clear();
        keyOrder.clear();
    }

    public static String formatWarningHeader(String warnAgent, String message, Instant timestamp) {
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        String dateStr = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC).format(timestamp);
        return "299 " + warnAgent + " \"" + escaped + "\" \"" + dateStr + "\"";
    }
}
