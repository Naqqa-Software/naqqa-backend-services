package com.naqqa.sso.json;

import com.naqqa.sso.SsoException;

public record JsonLimits(int maxDepth, int maxSize) {
    public static final int DEFAULT_MAX_DEPTH = 64;
    public static final int DEFAULT_MAX_SIZE = 1024 * 1024;
    public static final JsonLimits DEFAULT = new JsonLimits(DEFAULT_MAX_DEPTH, DEFAULT_MAX_SIZE);

    public JsonLimits {
        if (maxDepth < 1 || maxDepth > DEFAULT_MAX_DEPTH || maxSize < 2) {
            throw new SsoException("json.limits_invalid");
        }
    }

    public static JsonLimits ofSize(int maxSize) {
        return new JsonLimits(DEFAULT_MAX_DEPTH, maxSize);
    }
}
