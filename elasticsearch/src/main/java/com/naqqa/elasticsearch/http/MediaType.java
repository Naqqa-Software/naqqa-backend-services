package com.naqqa.elasticsearch.http;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class MediaType {

    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;
    private final double quality;

    public MediaType(String type, String subtype, Map<String, String> parameters, double quality) {
        this.type = type;
        this.subtype = subtype;
        this.parameters = parameters;
        this.quality = quality;
    }

    public String type() {
        return type;
    }

    public String subtype() {
        return subtype;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public double quality() {
        return quality;
    }

    public String parameter(String name) {
        return parameters.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isVendorElasticsearch() {
        return type.equals("application") && subtype.startsWith("vnd.elasticsearch+");
    }

    public String effectiveSubtype() {
        if (isVendorElasticsearch()) {
            return subtype.substring("vnd.elasticsearch+".length());
        }
        return subtype;
    }

    public Integer compatibleWithVersion() {
        String value = parameter("compatible-with");
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public boolean isNdjson() {
        String sub = effectiveSubtype();
        return sub.equalsIgnoreCase("x-ndjson") || sub.equalsIgnoreCase("ndjson");
    }

    public XContentType canonical() {
        String sub = effectiveSubtype().toLowerCase(Locale.ROOT);
        if (sub.contains("json")) {
            return XContentType.JSON;
        }
        if (sub.contains("smile")) {
            return XContentType.SMILE;
        }
        if (sub.contains("yaml")) {
            return XContentType.YAML;
        }
        if (sub.contains("cbor")) {
            return XContentType.CBOR;
        }
        return null;
    }

    public static MediaType parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split(";");
        String[] typeSub = parts[0].trim().split("/", 2);
        if (typeSub.length != 2) {
            return null;
        }
        String type = typeSub[0].trim().toLowerCase(Locale.ROOT);
        String subtype = typeSub[1].trim().toLowerCase(Locale.ROOT);
        Map<String, String> params = new LinkedHashMap<>();
        double quality = 1.0;
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i].trim();
            if (param.isEmpty()) {
                continue;
            }
            int eq = param.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = param.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = param.substring(eq + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (key.equals("q")) {
                try {
                    quality = Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                }
            } else {
                params.put(key, value);
            }
        }
        return new MediaType(type, subtype, params, quality);
    }

    public static java.util.List<MediaType> parseAccept(String header) {
        java.util.List<MediaType> result = new java.util.ArrayList<>();
        if (header == null || header.isEmpty()) {
            return result;
        }
        for (String piece : header.split(",")) {
            MediaType mediaType = parse(piece);
            if (mediaType != null) {
                result.add(mediaType);
            }
        }
        result.sort((a, b) -> Double.compare(b.quality(), a.quality()));
        return result;
    }
}
