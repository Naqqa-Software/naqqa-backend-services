package com.naqqa.auth.service.security;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.sso.password.PasswordHasher;
import com.naqqa.sso.password.PasswordPolicy;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Slf4j
@Service
public class PasswordService {

    public static final String KEYED_CODE_PREFIX = "m:";
    public static final String DIGEST_CODE_PREFIX = "s:";

    private final PasswordHasher hasher;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final boolean rehashLegacy;
    private final String bcryptDummy;
    private final PasswordPolicy policy;
    private final byte[] codeKey;

    public PasswordService(SsoProperties properties, Environment environment) {
        byte[] pepper = SsoSecrets.optionalKey(environment, properties.getSecrets().getPepperEnv());
        if (pepper == null) {
            if (properties.isEnabled()) {
                throw new IllegalStateException("naqqa.sso.enabled=true requires the pepper in " + properties.getSecrets().getPepperEnv());
            }
            log.warn("No password pepper configured in {}; new password hashes use BCrypt", properties.getSecrets().getPepperEnv());
            this.hasher = null;
            this.codeKey = null;
        } else {
            this.hasher = new PasswordHasher(pepper, properties.getPassword().getIterations());
            this.codeKey = SsoSecrets.derive(pepper, "naqqa-auth/one-time-codes");
        }
        this.rehashLegacy = properties.getPassword().isRehashLegacy();
        this.bcryptDummy = bcrypt.encode(Bytes.randomToken(16));
        this.policy = properties.isEnabled() ? PasswordPolicy.builder().minLength(properties.getPassword().getMinLength())
                .maxLength(properties.getPassword().getMaxLength()).build() : null;
    }

    public String sealCode(String purpose, String binding, String code) {
        byte[] data = Bytes.utf8(purpose + "|" + binding + "|" + code);
        return codeKey != null ? KEYED_CODE_PREFIX + Bytes.hex(Hashes.hmacSha256(codeKey, data))
                : DIGEST_CODE_PREFIX + Bytes.hex(Hashes.sha256(data));
    }

    public boolean codeMatches(String stored, String purpose, String binding, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        String candidate = stored.startsWith(KEYED_CODE_PREFIX) || stored.startsWith(DIGEST_CODE_PREFIX)
                ? sealCode(purpose, binding, presented) : presented;
        return Bytes.constantTimeEquals(Bytes.utf8(stored), Bytes.utf8(candidate));
    }

    public void enforcePolicy(String raw, Collection<String> context) {
        if (policy != null) {
            policy.enforce(raw, context);
        }
    }

    public static boolean isBcrypt(String encoded) {
        return encoded != null && encoded.length() == 60
                && (encoded.startsWith("$2a$") || encoded.startsWith("$2b$") || encoded.startsWith("$2y$"));
    }

    public static boolean isPbkdf2(String encoded) {
        return encoded != null && encoded.startsWith("$" + PasswordHasher.ID + "$");
    }

    public boolean usesPbkdf2() {
        return hasher != null;
    }

    public String hash(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("password required");
        }
        return hasher != null ? hasher.hash(raw) : bcrypt.encode(raw);
    }

    public boolean matches(String raw, String encoded) {
        if (raw == null || encoded == null) {
            dummy(raw);
            return false;
        }
        if (isPbkdf2(encoded)) {
            if (hasher == null) {
                return false;
            }
            try {
                return hasher.verify(raw, encoded);
            } catch (RuntimeException e) {
                return false;
            }
        }
        if (isBcrypt(encoded)) {
            return bcrypt.matches(raw, encoded);
        }
        dummy(raw);
        return false;
    }

    public void dummy(String raw) {
        String candidate = raw == null ? "" : raw;
        if (hasher != null) {
            try {
                hasher.verifyUnknownUser(candidate);
            } catch (RuntimeException ignored) {
                bcrypt.matches(candidate, bcryptDummy);
            }
        } else {
            bcrypt.matches(candidate, bcryptDummy);
        }
    }

    public boolean needsRehash(String encoded) {
        if (hasher == null || encoded == null) {
            return false;
        }
        if (isBcrypt(encoded)) {
            return rehashLegacy;
        }
        if (isPbkdf2(encoded)) {
            try {
                return hasher.needsRehash(encoded);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
