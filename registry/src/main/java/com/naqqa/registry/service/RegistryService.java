package com.naqqa.registry.service;

import com.naqqa.commons.logging.OperationLogger;
import com.naqqa.commons.web.PageResponse;
import com.naqqa.registry.core.PackageNames;
import com.naqqa.registry.core.RegistryTokens;
import com.naqqa.registry.core.SemVer;
import com.naqqa.registry.domain.PackageCatalog;
import com.naqqa.registry.domain.PackageEntry;
import com.naqqa.registry.domain.RegistryToken;
import com.naqqa.registry.domain.TokenStatus;
import com.naqqa.registry.dto.RegistryDtos.IssueTokenRequest;
import com.naqqa.registry.dto.RegistryDtos.IssuedToken;
import com.naqqa.registry.dto.RegistryDtos.PackageSummary;
import com.naqqa.registry.dto.RegistryDtos.RegistryCatalog;
import com.naqqa.registry.dto.RegistryDtos.TokenValidation;
import com.naqqa.registry.dto.RegistryDtos.TokenView;
import com.naqqa.registry.spi.PackageSource;
import com.naqqa.registry.spi.RegistryTokenStore;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class RegistryService {

    static final Pattern CUSTOMER_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$");
    static final int MAX_TEXT = 200;
    static final int MAX_REASON = 500;
    static final int MAX_SETS = 50;
    static final int MAX_PACKAGES = 200;
    static final Duration TOUCH_RESOLUTION = Duration.ofMinutes(1);

    private static final OperationLogger log = OperationLogger.forModule("registry", RegistryService.class);

    private final RegistryTokenStore store;
    private final RegistryTokens tokens;
    private final PackageSource packages;
    private final RegistrySettings settings;
    private final Clock clock;

    public RegistryService(RegistryTokenStore store, RegistryTokens tokens, PackageSource packages, RegistrySettings settings, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.packages = packages;
        this.settings = settings;
        this.clock = clock;
    }

    public IssuedToken issue(IssueTokenRequest request, String admin) {
        if (request == null) {
            throw RegistryErrors.invalid("The request body is required.");
        }
        String customerId = trim(request.customerId());
        if (customerId == null || !CUSTOMER_ID.matcher(customerId).matches()) {
            throw RegistryErrors.invalid("customerId is required: 1-128 characters, letters, digits and . _ : @ -");
        }
        String customerName = optionalText(request.customerName(), "customerName", MAX_TEXT);
        String label = optionalText(request.label(), "label", MAX_TEXT);
        List<String> sets = distinct(request.packageSets(), "packageSets", MAX_SETS);
        for (String set : sets) {
            if (!settings.packageSets().containsKey(set)) {
                throw RegistryErrors.invalid("Unknown package set '" + set + "'. Known sets: " + settings.packageSets().keySet());
            }
        }
        List<String> grants = distinct(request.packages(), "packages", MAX_PACKAGES);
        for (String pattern : grants) {
            if (!PackageNames.isValidPattern(pattern)) {
                throw RegistryErrors.invalid("Invalid package '" + pattern + "': use a package name or a scope wildcard like @naqqa/*");
            }
        }
        if (sets.isEmpty() && grants.isEmpty()) {
            throw RegistryErrors.invalid("Grant at least one package set or package.");
        }
        Instant now = now();
        Instant expiresAt = expiry(request, now);
        RegistryTokens.Minted minted = tokens.mint();
        RegistryToken token = new RegistryToken(minted.id(), tokens.hash(minted.raw()), customerId, customerName, label, sets, grants,
                now, admin, expiresAt, null, null, null, null);
        store.save(token);
        log.info("token.issue", "issued registry token {} for customer {} by {}", new Object[]{token.id(), customerId, admin});
        return new IssuedToken(minted.raw(), view(token, now));
    }

    public TokenView revoke(String id, String reason, String admin) {
        RegistryToken token = store.findById(id).orElseThrow(RegistryErrors::tokenNotFound);
        Instant now = now();
        if (token.revokedAt() != null) {
            return view(token, now);
        }
        RegistryToken revoked = token.revoke(now, admin, optionalText(reason, "reason", MAX_REASON));
        store.save(revoked);
        log.info("token.revoke", "revoked registry token {} of customer {} by {}", new Object[]{id, token.customerId(), admin});
        return view(revoked, now);
    }

    public TokenView get(String id) {
        return store.findById(id).map(t -> view(t, now())).orElseThrow(RegistryErrors::tokenNotFound);
    }

    public PageResponse<TokenView> list(String customerId, String status, int page, int size) {
        Instant now = now();
        String wanted = status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (wanted != null && !wanted.equals("all") && List.of(TokenStatus.values()).stream().noneMatch(s -> s.value().equals(wanted))) {
            throw RegistryErrors.invalid("status must be one of all, active, expired, revoked");
        }
        List<TokenView> views = store.list(trim(customerId)).stream()
                .filter(t -> wanted == null || wanted.equals("all") || t.status(now).value().equals(wanted))
                .map(t -> view(t, now))
                .toList();
        return PageResponse.fromAll(views, page, Math.min(size, 200), 50);
    }

    public RegistryToken authenticate(String authorization) {
        String raw = extractToken(authorization);
        RegistryTokens.Parsed parsed = RegistryTokens.parse(raw).orElseThrow(RegistryErrors::tokenInvalid);
        RegistryToken token = store.findById(parsed.id()).orElseThrow(RegistryErrors::tokenInvalid);
        if (!tokens.matches(raw, token.hash())) {
            throw RegistryErrors.tokenInvalid();
        }
        Instant now = now();
        switch (token.status(now)) {
            case REVOKED -> throw RegistryErrors.tokenRevoked();
            case EXPIRED -> throw RegistryErrors.tokenExpired();
            default -> {
            }
        }
        if (token.lastUsedAt() == null || !now.isBefore(token.lastUsedAt().plus(TOUCH_RESOLUTION))) {
            store.touch(token.id(), now);
        }
        return token;
    }

    public TokenValidation validate(String authorization) {
        RegistryToken token = authenticate(authorization);
        return new TokenValidation(true, token.id(), token.customerId(), token.expiresAt(),
                List.copyOf(grantedPackages(token, packages.catalog())));
    }

    public PackageCatalog catalog() {
        return packages.catalog();
    }

    public Optional<PackageEntry> grantedPackage(RegistryToken token, String name) {
        PackageCatalog catalog = packages.catalog();
        if (!PackageNames.isValid(name) || !grantedPackages(token, catalog).contains(name)) {
            return Optional.empty();
        }
        return catalog.find(name);
    }

    public Optional<Resource> tarball(String name, String version) {
        return packages.tarball(name, version);
    }

    public SortedSet<String> grantedPackages(RegistryToken token, PackageCatalog catalog) {
        List<String> patterns = new ArrayList<>(token.packages());
        for (String set : token.packageSets()) {
            patterns.addAll(settings.packageSets().getOrDefault(set, List.of()));
        }
        SortedSet<String> granted = new TreeSet<>();
        for (String name : catalog.names()) {
            if (patterns.stream().anyMatch(p -> PackageNames.matches(p, name))) {
                granted.add(name);
            }
        }
        if (settings.includeDependencies()) {
            Deque<String> queue = new ArrayDeque<>(granted);
            while (!queue.isEmpty()) {
                catalog.find(queue.pop()).ifPresent(entry -> {
                    for (String dependency : entry.dependencies()) {
                        if (catalog.find(dependency).isPresent() && granted.add(dependency)) {
                            queue.add(dependency);
                        }
                    }
                });
            }
        }
        return granted;
    }

    public RegistryCatalog describe() {
        List<PackageSummary> summaries = packages.catalog().packages().values().stream()
                .map(e -> new PackageSummary(e.name(), e.latest(), sortedVersions(e)))
                .toList();
        return new RegistryCatalog(settings.packageSets(), summaries);
    }

    public TokenView view(RegistryToken t, Instant now) {
        return new TokenView(t.id(), RegistryTokens.mask(t.id()), t.customerId(), t.customerName(), t.label(), t.packageSets(),
                t.packages(), t.status(now).value(), t.createdAt(), t.createdBy(), t.expiresAt(), t.revokedAt(), t.revokedBy(),
                t.revokeReason(), t.lastUsedAt());
    }

    public static List<String> sortedVersions(PackageEntry entry) {
        return entry.versions().keySet().stream()
                .sorted(Comparator.comparing(v -> SemVer.parse(v).orElseThrow()))
                .toList();
    }

    static String extractToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw RegistryErrors.tokenMissing();
        }
        String value = authorization.trim();
        int space = value.indexOf(' ');
        if (space < 0) {
            throw RegistryErrors.tokenInvalid();
        }
        String scheme = value.substring(0, space).toLowerCase(Locale.ROOT);
        String credentials = value.substring(space + 1).trim();
        if (scheme.equals("bearer")) {
            return credentials;
        }
        if (scheme.equals("basic")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(credentials), StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon >= 0) {
                    return decoded.substring(colon + 1);
                }
            } catch (IllegalArgumentException e) {
                throw RegistryErrors.tokenInvalid();
            }
        }
        throw RegistryErrors.tokenInvalid();
    }

    private Instant expiry(IssueTokenRequest request, Instant now) {
        if (request.expiresAt() != null && request.ttlDays() != null) {
            throw RegistryErrors.invalid("Send either expiresAt or ttlDays, not both.");
        }
        Instant limit = now.plus(settings.maxTtl());
        if (request.ttlDays() != null) {
            long maxDays = settings.maxTtl().toDays();
            if (request.ttlDays() < 1 || request.ttlDays() > maxDays) {
                throw RegistryErrors.invalid("ttlDays must be between 1 and " + maxDays);
            }
            return now.plus(Duration.ofDays(request.ttlDays()));
        }
        if (request.expiresAt() != null) {
            Instant at = request.expiresAt().truncatedTo(ChronoUnit.MILLIS);
            if (!at.isAfter(now) || at.isAfter(limit)) {
                throw RegistryErrors.invalid("expiresAt must be in the future and at most " + settings.maxTtl().toDays() + " days away");
            }
            return at;
        }
        return now.plus(settings.defaultTtl());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static List<String> distinct(List<String> values, String field, int max) {
        if (values == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String v = trim(value);
            if (v == null) {
                throw RegistryErrors.invalid(field + " must not contain empty values");
            }
            out.add(v);
        }
        if (out.size() > max) {
            throw RegistryErrors.invalid(field + " accepts at most " + max + " entries");
        }
        return List.copyOf(out);
    }

    private static String optionalText(String value, String field, int max) {
        String v = trim(value);
        if (v != null && v.length() > max) {
            throw RegistryErrors.invalid(field + " must be at most " + max + " characters");
        }
        return v;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
