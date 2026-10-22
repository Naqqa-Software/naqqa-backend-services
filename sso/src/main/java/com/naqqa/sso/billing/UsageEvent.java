package com.naqqa.sso.billing;

import java.time.Instant;
import java.util.Map;

public record UsageEvent(Instant at, String tenantId, Metric metric, long amount, String subject, Map<String, String> attributes) {

    public UsageEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"at\":\"").append(at).append("\",\"tenant\":");
        str(sb, tenantId);
        sb.append(",\"metric\":\"").append(metric.name()).append("\",\"amount\":").append(amount);
        if (subject != null) {
            sb.append(",\"subject\":");
            str(sb, subject);
        }
        if (!attributes.isEmpty()) {
            sb.append(",\"attributes\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(attributes).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                str(sb, e.getKey());
                sb.append(':');
                str(sb, e.getValue());
            }
            sb.append('}');
        }
        return sb.append('}').toString();
    }

    private static void str(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
