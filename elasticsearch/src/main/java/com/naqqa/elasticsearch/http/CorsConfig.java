package com.naqqa.elasticsearch.http;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class CorsConfig {

    private final boolean enabled;
    private final boolean allowAnyOrigin;
    private final List<String> allowOriginLiterals;
    private final List<Pattern> allowOriginPatterns;
    private final List<String> allowMethods;
    private final List<String> allowHeaders;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;

    private CorsConfig(boolean enabled, boolean allowAnyOrigin, List<String> allowOriginLiterals,
                        List<Pattern> allowOriginPatterns, List<String> allowMethods, List<String> allowHeaders,
                        boolean allowCredentials, long maxAgeSeconds) {
        this.enabled = enabled;
        this.allowAnyOrigin = allowAnyOrigin;
        this.allowOriginLiterals = allowOriginLiterals;
        this.allowOriginPatterns = allowOriginPatterns;
        this.allowMethods = allowMethods;
        this.allowHeaders = allowHeaders;
        this.allowCredentials = allowCredentials;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public static CorsConfig disabled() {
        return new CorsConfig(false, false, List.of(), List.of(), List.of(), List.of(), false, 1728000);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean allowAnyOrigin() {
        return allowAnyOrigin;
    }

    public List<String> allowMethods() {
        return allowMethods;
    }

    public List<String> allowHeaders() {
        return allowHeaders;
    }

    public boolean allowCredentials() {
        return allowCredentials;
    }

    public long maxAgeSeconds() {
        return maxAgeSeconds;
    }

    public boolean matchesOrigin(String origin) {
        if (origin == null) {
            return false;
        }
        if (allowAnyOrigin) {
            return true;
        }
        for (String literal : allowOriginLiterals) {
            if (literal.equals(origin)) {
                return true;
            }
        }
        for (Pattern pattern : allowOriginPatterns) {
            if (pattern.matcher(origin).matches()) {
                return true;
            }
        }
        return false;
    }

    public static final class Builder {
        private boolean enabled = false;
        private boolean allowAnyOrigin = false;
        private final List<String> allowOriginLiterals = new ArrayList<>();
        private final List<Pattern> allowOriginPatterns = new ArrayList<>();
        private List<String> allowMethods = List.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");
        private List<String> allowHeaders = List.of("X-Requested-With", "Content-Type", "Content-Length", "Authorization");
        private boolean allowCredentials = false;
        private long maxAgeSeconds = 1728000;

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder allowOrigin(String value) {
            if (value == null || value.isEmpty()) {
                return this;
            }
            if (value.equals("*")) {
                allowAnyOrigin = true;
            } else if (value.startsWith("/") && value.endsWith("/") && value.length() > 1) {
                allowOriginPatterns.add(Pattern.compile(value.substring(1, value.length() - 1)));
            } else {
                for (String part : value.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        allowOriginLiterals.add(trimmed);
                    }
                }
            }
            return this;
        }

        public Builder allowMethods(List<String> methods) {
            this.allowMethods = methods;
            return this;
        }

        public Builder allowHeaders(List<String> headers) {
            this.allowHeaders = headers;
            return this;
        }

        public Builder allowCredentials(boolean value) {
            this.allowCredentials = value;
            return this;
        }

        public Builder maxAgeSeconds(long value) {
            this.maxAgeSeconds = value;
            return this;
        }

        public CorsConfig build() {
            return new CorsConfig(enabled, allowAnyOrigin, List.copyOf(allowOriginLiterals),
                List.copyOf(allowOriginPatterns), List.copyOf(allowMethods), List.copyOf(allowHeaders),
                allowCredentials, maxAgeSeconds);
        }
    }
}
