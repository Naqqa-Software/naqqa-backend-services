package com.naqqa.observability.ingest;

public record IngestLimits(int maxRecords, int maxMessageChars, int maxAttributeChars, int maxAttributes, int maxFrames,
                           long maxFutureSkewMs, long maxAgeMs) {

    public static final IngestLimits DEFAULT = new IngestLimits(1000, 8192, 1024, 64, 200,
            5L * 60 * 1000, 30L * 24 * 3600 * 1000);

    public String message(String value) {
        return clip(value, maxMessageChars);
    }

    public String attribute(String value) {
        return clip(value, maxAttributeChars);
    }

    static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
