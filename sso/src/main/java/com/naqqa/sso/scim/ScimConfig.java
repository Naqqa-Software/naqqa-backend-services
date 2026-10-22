package com.naqqa.sso.scim;

public record ScimConfig(
        String baseUrl,
        int defaultCount,
        int maxCount,
        int maxFilterLength,
        int maxFilterTokens,
        int maxFilterDepth,
        int maxPatchOperations,
        boolean requireIfMatch) {

    public static final int MAX_COUNT = 200;

    public ScimConfig {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new com.naqqa.sso.SsoException("scim.config_invalid");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (maxCount < 1 || maxCount > MAX_COUNT || defaultCount < 0 || defaultCount > maxCount
                || maxFilterLength < 1 || maxFilterTokens < 1 || maxFilterDepth < 1 || maxPatchOperations < 1) {
            throw new com.naqqa.sso.SsoException("scim.config_invalid");
        }
    }

    public static ScimConfig defaults(String baseUrl) {
        return new ScimConfig(baseUrl, 100, MAX_COUNT, 2048, 512, 16, 100, false);
    }

    public FilterParser.Limits filterLimits() {
        return new FilterParser.Limits(maxFilterLength, maxFilterTokens, maxFilterDepth);
    }
}
