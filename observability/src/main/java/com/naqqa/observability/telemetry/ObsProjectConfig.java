package com.naqqa.observability.telemetry;

import java.time.Instant;
import java.util.List;

public record ObsProjectConfig(String projectId, String env, TokenMeta ingest, TokenMeta management,
                               List<String> originAllowlist, Retention retention, boolean ingestEnabled) {

    public static final List<String> INGEST_SCOPES = List.of("logs:write", "metrics:write", "traces:write", "errors:write", "analytics:write");
    public static final List<String> MANAGEMENT_SCOPES = List.of("logs:read", "metrics:read", "issues:read", "issues:write",
            "analytics:read", "tokens:manage", "settings:manage");

    public ObsProjectConfig {
        originAllowlist = originAllowlist == null ? List.of() : List.copyOf(originAllowlist);
        retention = retention == null ? Retention.DEFAULT : retention;
    }

    public record TokenMeta(String id, String kind, String env, String hash, String masked, List<String> scopes,
                            Instant createdAt, Instant lastRotatedAt, Instant lastUsedAt) {

        public TokenMeta {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        public TokenMeta used(Instant at) {
            return new TokenMeta(id, kind, env, hash, masked, scopes, createdAt, lastRotatedAt, at);
        }
    }

    public record Retention(int logsDays, int metricsDays, int tracesDays, int issuesDays) {

        static final List<Integer> ALLOWED = List.of(7, 14, 30, 60, 90, 180, 365);
        public static final Retention DEFAULT = new Retention(30, 90, 7, 180);

        public Retention {
            logsDays = allowed(logsDays, 30);
            metricsDays = allowed(metricsDays, 90);
            tracesDays = allowed(tracesDays, 7);
            issuesDays = allowed(issuesDays, 180);
        }

        private static int allowed(int value, int fallback) {
            return ALLOWED.contains(value) ? value : fallback;
        }
    }

    public ObsProjectConfig withIngest(TokenMeta token) {
        return new ObsProjectConfig(projectId, env, token, management, originAllowlist, retention, ingestEnabled);
    }

    public ObsProjectConfig withManagement(TokenMeta token) {
        return new ObsProjectConfig(projectId, env, ingest, token, originAllowlist, retention, ingestEnabled);
    }

    public ObsProjectConfig withSettings(List<String> origins, Retention newRetention, boolean enabled) {
        return new ObsProjectConfig(projectId, env, ingest, management, origins, newRetention, enabled);
    }
}
