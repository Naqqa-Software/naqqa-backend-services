package com.naqqa.elasticsearch.common.xcontent;

public enum XContentType {

    JSON("application/json", "json"),
    SMILE("application/smile", "smile"),
    YAML("application/yaml", "yaml"),
    CBOR("application/cbor", "cbor");

    private final String mediaType;
    private final String shortName;

    XContentType(String mediaType, String shortName) {
        this.mediaType = mediaType;
        this.shortName = shortName;
    }

    public String mediaType() {
        return mediaType;
    }

    public String shortName() {
        return shortName;
    }

    public static XContentType fromMediaType(String mediaTypeHeader) {
        if (mediaTypeHeader == null) {
            return null;
        }
        String header = mediaTypeHeader.toLowerCase(java.util.Locale.ROOT);
        int semi = header.indexOf(';');
        if (semi >= 0) {
            header = header.substring(0, semi);
        }
        header = header.trim();
        for (XContentType type : values()) {
            if (header.equals(type.mediaType) || header.endsWith("/" + type.shortName) || header.endsWith("+" + type.shortName)) {
                return type;
            }
        }
        if (header.contains("ndjson")) {
            return JSON;
        }
        return null;
    }

    public static XContentType of(byte[] data) {
        return of(data, 0, data.length);
    }

    public static XContentType of(byte[] data, int offset, int length) {
        if (length == 0) {
            return null;
        }
        int i = offset;
        int end = offset + length;
        while (i < end && isWhitespace(data[i])) {
            i++;
        }
        if (i >= end) {
            return null;
        }
        int b0 = data[i] & 0xFF;
        if (length - (i - offset) >= 2) {
            int b1 = data[i + 1] & 0xFF;
            if (b0 == 0x3A && b1 == 0x29) {
                return SMILE;
            }
        }
        if (b0 == '{' || b0 == '[' || b0 == '"') {
            return JSON;
        }
        if ((b0 & 0xE0) == 0x80 || (b0 & 0xE0) == 0xA0 || b0 == 0xBF || (b0 >= 0x00 && b0 <= 0x1B) || (b0 >= 0xC0 && b0 <= 0xFB)) {
            return CBOR;
        }
        return JSON;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }
}
