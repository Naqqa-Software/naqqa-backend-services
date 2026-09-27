package com.naqqa.elasticsearch.action.byquery;

import java.util.Map;

public record ByQueryOptions(int batchSize, double requestsPerSecond, long maxDocs, long timeoutMillis,
                              String preference, String refresh, int retryOnConflict, boolean abortOnConflict,
                              String opType) {

    public static final ByQueryOptions DEFAULT =
        new ByQueryOptions(1000, -1d, -1L, 30_000L, null, "false", 0, true, "index");

    public ByQueryOptions withBatchSize(int newBatchSize) {
        return new ByQueryOptions(newBatchSize, requestsPerSecond, maxDocs, timeoutMillis, preference, refresh,
            retryOnConflict, abortOnConflict, opType);
    }

    public ByQueryOptions withRequestsPerSecond(double newRate) {
        return new ByQueryOptions(batchSize, newRate, maxDocs, timeoutMillis, preference, refresh, retryOnConflict,
            abortOnConflict, opType);
    }

    public static ByQueryOptions fromParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return DEFAULT;
        }
        int batchSize = intParam(params, "scroll_size", DEFAULT.batchSize());
        double rps = doubleParam(params, "requests_per_second", DEFAULT.requestsPerSecond());
        long maxDocs = longParam(params, "max_docs", DEFAULT.maxDocs());
        long timeoutMillis = DEFAULT.timeoutMillis();
        String preference = params.get("preference");
        String refresh = params.getOrDefault("refresh", DEFAULT.refresh());
        int retryOnConflict = intParam(params, "retry_on_conflict", DEFAULT.retryOnConflict());
        boolean abortOnConflict = !"proceed".equalsIgnoreCase(params.get("conflicts"));
        String opType = params.getOrDefault("op_type", DEFAULT.opType());
        return new ByQueryOptions(batchSize, rps, maxDocs, timeoutMillis, preference, refresh, retryOnConflict,
            abortOnConflict, opType);
    }

    private static int intParam(Map<String, String> params, String key, int def) {
        String raw = params.get(key);
        return raw == null ? def : Integer.parseInt(raw.trim());
    }

    private static long longParam(Map<String, String> params, String key, long def) {
        String raw = params.get(key);
        return raw == null ? def : Long.parseLong(raw.trim());
    }

    private static double doubleParam(Map<String, String> params, String key, double def) {
        String raw = params.get(key);
        if (raw == null) {
            return def;
        }
        if ("-1".equals(raw.trim()) || "unlimited".equalsIgnoreCase(raw.trim())) {
            return -1d;
        }
        return Double.parseDouble(raw.trim());
    }
}
