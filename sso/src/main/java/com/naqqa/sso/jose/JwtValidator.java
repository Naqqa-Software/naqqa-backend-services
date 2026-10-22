package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.crypto.KeyRing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JwtValidator {
    public static final Duration DEFAULT_LEEWAY = Duration.ofSeconds(60);
    public static final Duration MAX_LEEWAY = Duration.ofMinutes(5);

    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration leeway;
    private final Duration maxLifetime;
    private final String type;
    private final boolean requireExp;
    private final boolean requireIat;
    private final Set<String> requiredClaims;

    private JwtValidator(Builder b) {
        this.clock = b.clock;
        this.issuer = b.issuer;
        this.audience = b.audience;
        this.leeway = b.leeway;
        this.maxLifetime = b.maxLifetime;
        this.type = b.type;
        this.requireExp = b.requireExp;
        this.requireIat = b.requireIat;
        this.requiredClaims = Set.copyOf(b.requiredClaims);
    }

    public static Builder builder(Clock clock) {
        return new Builder(clock);
    }

    public Jwt.Verified verify(String token, KeyRing ring) {
        return Jwt.verify(token, ring, this);
    }

    public void validate(Map<String, Object> header, JwtClaims claims) {
        if (type != null) {
            Object typ = header.get("typ");
            if (!(typ instanceof String) || !normalizeTyp((String) typ).equals(normalizeTyp(type))) {
                throw new SsoException("jwt.typ_mismatch");
            }
        }
        for (String c : requiredClaims) {
            if (claims.get(c) == null) {
                throw new SsoException("jwt.claim_missing");
            }
        }
        if (issuer != null && !issuer.equals(claims.issuer())) {
            throw new SsoException("jwt.issuer_mismatch");
        }
        if (audience != null && !claims.audience().contains(audience)) {
            throw new SsoException("jwt.audience_mismatch");
        }
        Instant now = clock.instant();
        Instant exp = claims.expiresAt();
        Instant nbf = claims.notBefore();
        Instant iat = claims.issuedAt();
        if (exp == null && requireExp) {
            throw new SsoException("jwt.exp_missing");
        }
        if (iat == null && requireIat) {
            throw new SsoException("jwt.iat_missing");
        }
        if (exp != null && !now.isBefore(exp.plus(leeway))) {
            throw new SsoException("jwt.expired");
        }
        if (nbf != null && now.plus(leeway).isBefore(nbf)) {
            throw new SsoException("jwt.not_yet_valid");
        }
        if (iat != null && iat.isAfter(now.plus(leeway))) {
            throw new SsoException("jwt.iat_future");
        }
        if (maxLifetime != null && exp != null) {
            Instant start = iat != null ? iat : (nbf != null ? nbf : now);
            if (Duration.between(start, exp).compareTo(maxLifetime) > 0) {
                throw new SsoException("jwt.lifetime_exceeded");
            }
        }
    }

    static String normalizeTyp(String typ) {
        String t = typ.toLowerCase(Locale.ROOT);
        return t.startsWith("application/") ? t.substring("application/".length()) : t;
    }

    public static final class Builder {
        private final Clock clock;
        private String issuer;
        private String audience;
        private Duration leeway = DEFAULT_LEEWAY;
        private Duration maxLifetime;
        private String type;
        private boolean requireExp = true;
        private boolean requireIat;
        private final Set<String> requiredClaims = new LinkedHashSet<>();
        private boolean anyAudience;

        private Builder(Clock clock) {
            if (clock == null) {
                throw new SsoException("jwt.clock_required");
            }
            this.clock = clock;
        }

        public Builder issuer(String value) {
            this.issuer = value;
            return this;
        }

        public Builder audience(String value) {
            this.audience = value;
            return this;
        }

        public Builder leeway(Duration value) {
            if (value == null || value.isNegative() || value.compareTo(MAX_LEEWAY) > 0) {
                throw new SsoException("jwt.leeway_invalid");
            }
            this.leeway = value;
            return this;
        }

        public Builder maxLifetime(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new SsoException("jwt.lifetime_invalid");
            }
            this.maxLifetime = value;
            return this;
        }

        public Builder type(String value) {
            this.type = value;
            return this;
        }

        public Builder requireExp(boolean value) {
            this.requireExp = value;
            return this;
        }

        public Builder requireIat(boolean value) {
            this.requireIat = value;
            return this;
        }

        public Builder requireClaim(String name) {
            this.requiredClaims.add(name);
            return this;
        }

        public Builder anyAudience() {
            this.anyAudience = true;
            return this;
        }

        public JwtValidator build() {
            if (issuer == null) {
                throw new SsoException("jwt.issuer_required");
            }
            if (audience == null && !anyAudience) {
                throw new SsoException("jwt.audience_required");
            }
            return new JwtValidator(this);
        }
    }
}
