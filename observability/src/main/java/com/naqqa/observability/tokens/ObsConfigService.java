package com.naqqa.observability.tokens;

import com.naqqa.commons.error.ApiException;
import com.naqqa.observability.spi.ObsConfigStore;
import com.naqqa.observability.telemetry.ObsProjectConfig;
import com.naqqa.observability.telemetry.ObsProjectConfig.Retention;
import com.naqqa.observability.telemetry.ObsProjectConfig.TokenMeta;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class ObsConfigService {

    static final Duration USAGE_WRITE_INTERVAL = Duration.ofMinutes(1);
    static final int MAX_ORIGINS = 50;

    private final ObsConfigStore store;
    private final ObsTokens tokens;
    private final Clock clock;
    private final String defaultEnv;

    public ObsConfigService(ObsConfigStore store, ObsTokens tokens, Clock clock, String defaultEnv) {
        this.store = store;
        this.tokens = tokens;
        this.clock = clock;
        this.defaultEnv = "test".equals(defaultEnv) ? "test" : "live";
    }

    public record TokenView(String id, String kind, String env, String token, List<String> scopes, Instant createdAt,
                            Instant lastRotatedAt, Instant lastUsedAt) {
    }

    public record ConfigView(String projectId, String env, TokenView ingest, TokenView management,
                             List<String> originAllowlist, Retention retention, boolean ingestEnabled) {
    }

    public record Authorized(String projectId, TokenMeta token) {
    }

    public synchronized ConfigView view(String projectId) {
        requireProject(projectId);
        Optional<ObsProjectConfig> existing = store.find(projectId);
        if (existing.isPresent()) {
            return toView(existing.get(), null, null);
        }
        Instant now = clock.instant();
        ObsTokens.Minted ingest = tokens.mint(ObsTokens.INGEST, defaultEnv);
        ObsTokens.Minted management = tokens.mint(ObsTokens.MANAGEMENT, defaultEnv);
        ObsProjectConfig config = new ObsProjectConfig(projectId, defaultEnv,
                meta(ingest, ObsProjectConfig.INGEST_SCOPES, now, null),
                meta(management, ObsProjectConfig.MANAGEMENT_SCOPES, now, null),
                List.of(), Retention.DEFAULT, true);
        store.save(config);
        return toView(config, ingest.raw(), management.raw());
    }

    public synchronized ConfigView rotate(String projectId, String kind) {
        view(projectId);
        ObsProjectConfig config = store.find(projectId).orElseThrow();
        Instant now = clock.instant();
        if (ObsTokens.INGEST.equals(kind)) {
            ObsTokens.Minted minted = tokens.mint(ObsTokens.INGEST, config.env());
            ObsProjectConfig next = config.withIngest(meta(minted, ObsProjectConfig.INGEST_SCOPES, config.ingest().createdAt(), now));
            store.save(next);
            return toView(next, minted.raw(), null);
        }
        if (ObsTokens.MANAGEMENT.equals(kind)) {
            ObsTokens.Minted minted = tokens.mint(ObsTokens.MANAGEMENT, config.env());
            ObsProjectConfig next = config.withManagement(meta(minted, ObsProjectConfig.MANAGEMENT_SCOPES, config.management().createdAt(), now));
            store.save(next);
            return toView(next, null, minted.raw());
        }
        throw new ApiException(400, "INVALID_REQUEST", "Unknown token kind.");
    }

    public synchronized ConfigView update(String projectId, List<String> origins, Retention retention, Boolean ingestEnabled) {
        view(projectId);
        ObsProjectConfig config = store.find(projectId).orElseThrow();
        List<String> cleanOrigins = origins == null ? config.originAllowlist() : normalizeOrigins(origins);
        ObsProjectConfig next = config.withSettings(cleanOrigins, retention == null ? config.retention() : retention,
                ingestEnabled == null ? config.ingestEnabled() : ingestEnabled);
        store.save(next);
        return toView(next, null, null);
    }

    public Optional<ObsProjectConfig> config(String projectId) {
        return store.find(projectId);
    }

    public Authorized authorizeIngest(String rawToken, String scope, String origin) {
        ObsTokens.Parsed parsed = ObsTokens.parse(rawToken)
                .orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "A valid ingest token is required."));
        if (!ObsTokens.INGEST.equals(parsed.kind())) {
            throw new ApiException(401, "UNAUTHORIZED", "A valid ingest token is required.");
        }
        ObsProjectConfig config = store.findByTokenId(parsed.id())
                .filter(c -> c.ingest() != null && parsed.id().equals(c.ingest().id()))
                .orElseThrow(() -> new ApiException(401, "UNAUTHORIZED", "A valid ingest token is required."));
        if (!tokens.matches(rawToken, config.ingest().hash())) {
            throw new ApiException(401, "UNAUTHORIZED", "A valid ingest token is required.");
        }
        if (!config.ingestEnabled()) {
            throw new ApiException(403, "INGEST_DISABLED", "Ingestion is disabled for this project.");
        }
        if (!config.ingest().scopes().contains(scope)) {
            throw new ApiException(403, "FORBIDDEN", "The token cannot write " + scope + ".");
        }
        if (origin != null && !origin.isBlank() && !config.originAllowlist().isEmpty()
                && !config.originAllowlist().contains(normalizeOrigin(origin))) {
            throw new ApiException(403, "ORIGIN_INVALID", "This origin may not send telemetry for the project.");
        }
        markUsed(config, true);
        return new Authorized(config.projectId(), config.ingest());
    }

    public boolean authorizeManagement(String rawToken, String projectId, String scope) {
        Optional<ObsTokens.Parsed> parsed = ObsTokens.parse(rawToken);
        if (parsed.isEmpty() || !ObsTokens.MANAGEMENT.equals(parsed.get().kind())) {
            return false;
        }
        Optional<ObsProjectConfig> config = store.find(projectId)
                .filter(c -> c.management() != null && parsed.get().id().equals(c.management().id()));
        if (config.isEmpty() || !tokens.matches(rawToken, config.get().management().hash())
                || !config.get().management().scopes().contains(scope)) {
            return false;
        }
        markUsed(config.get(), false);
        return true;
    }

    private void markUsed(ObsProjectConfig config, boolean ingest) {
        Instant now = clock.instant();
        TokenMeta token = ingest ? config.ingest() : config.management();
        if (token.lastUsedAt() != null && Duration.between(token.lastUsedAt(), now).compareTo(USAGE_WRITE_INTERVAL) < 0) {
            return;
        }
        synchronized (this) {
            ObsProjectConfig fresh = store.find(config.projectId()).orElse(config);
            if (ingest && fresh.ingest() != null && fresh.ingest().id().equals(token.id())) {
                store.save(fresh.withIngest(fresh.ingest().used(now)));
            } else if (!ingest && fresh.management() != null && fresh.management().id().equals(token.id())) {
                store.save(fresh.withManagement(fresh.management().used(now)));
            }
        }
    }

    static List<String> normalizeOrigins(List<String> origins) {
        List<String> out = new ArrayList<>();
        for (String origin : origins) {
            if (origin == null || origin.isBlank()) {
                continue;
            }
            String normalized = normalizeOrigin(origin);
            if (normalized == null) {
                throw new ApiException(400, "ORIGIN_INVALID", "An origin is not valid: " + origin);
            }
            if (!out.contains(normalized)) {
                out.add(normalized);
            }
        }
        if (out.size() > MAX_ORIGINS) {
            throw new ApiException(400, "ORIGIN_INVALID", "At most " + MAX_ORIGINS + " origins are allowed.");
        }
        return out;
    }

    static String normalizeOrigin(String origin) {
        try {
            URI uri = URI.create(origin.trim());
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme) || uri.getHost() == null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                    || uri.getQuery() != null || uri.getUserInfo() != null) {
                return null;
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TokenMeta meta(ObsTokens.Minted minted, List<String> scopes, Instant createdAt, Instant rotatedAt) {
        return new TokenMeta(minted.id(), minted.kind(), minted.env(), tokens.hash(minted.raw()), ObsTokens.mask(minted.raw()),
                scopes, createdAt, rotatedAt, null);
    }

    private static ConfigView toView(ObsProjectConfig c, String rawIngest, String rawManagement) {
        return new ConfigView(c.projectId(), c.env(), tokenView(c.ingest(), rawIngest), tokenView(c.management(), rawManagement),
                c.originAllowlist(), c.retention(), c.ingestEnabled());
    }

    private static TokenView tokenView(TokenMeta t, String raw) {
        if (t == null) {
            return null;
        }
        return new TokenView(t.id(), t.kind(), t.env(), raw != null ? raw : t.masked(), t.scopes(), t.createdAt(),
                t.lastRotatedAt(), t.lastUsedAt());
    }

    private static void requireProject(String projectId) {
        if (projectId == null || projectId.isBlank() || projectId.length() > 128) {
            throw new ApiException(400, "INVALID_REQUEST", "projectId is required.");
        }
    }
}
