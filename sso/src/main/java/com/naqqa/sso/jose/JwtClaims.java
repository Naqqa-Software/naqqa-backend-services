package com.naqqa.sso.jose;

import com.naqqa.sso.SsoException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record JwtClaims(Map<String, Object> values) {
    public static final long MAX_NUMERIC_DATE = 253402300799L;

    public JwtClaims {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Object get(String name) {
        return values.get(name);
    }

    public String string(String name) {
        Object v = values.get(name);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw new SsoException("jwt.claim_invalid");
        }
        return (String) v;
    }

    public String issuer() {
        return string("iss");
    }

    public String subject() {
        return string("sub");
    }

    public String jwtId() {
        return string("jti");
    }

    public List<String> audience() {
        Object v = values.get("aud");
        if (v == null) {
            return List.of();
        }
        if (v instanceof String) {
            return List.of((String) v);
        }
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) v) {
                if (!(o instanceof String)) {
                    throw new SsoException("jwt.claim_invalid");
                }
                out.add((String) o);
            }
            return Collections.unmodifiableList(out);
        }
        throw new SsoException("jwt.claim_invalid");
    }

    public Instant expiresAt() {
        return numericDate("exp");
    }

    public Instant notBefore() {
        return numericDate("nbf");
    }

    public Instant issuedAt() {
        return numericDate("iat");
    }

    public Instant numericDate(String name) {
        Object v = values.get(name);
        if (v == null) {
            return null;
        }
        long seconds;
        if (v instanceof Long) {
            seconds = (Long) v;
        } else if (v instanceof Integer) {
            seconds = (Integer) v;
        } else if (v instanceof BigDecimal) {
            BigDecimal d = ((BigDecimal) v).setScale(0, RoundingMode.FLOOR);
            if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(MAX_NUMERIC_DATE)) > 0) {
                throw new SsoException("jwt.claim_invalid");
            }
            seconds = d.longValue();
        } else {
            throw new SsoException("jwt.claim_invalid");
        }
        if (seconds < 0 || seconds > MAX_NUMERIC_DATE) {
            throw new SsoException("jwt.claim_invalid");
        }
        return Instant.ofEpochSecond(seconds);
    }
}
