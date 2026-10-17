package com.naqqa.auth.sso;

import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SsoSecrets {

    public static final int MIN_KEY_BYTES = 32;

    private final byte[] pepper;
    private final byte[] sealingKey;
    private final String sealingKeyId;
    private final byte[] auditKey;
    private final String auditKeyId;
    private final java.util.Map<String, byte[]> auditRing = new java.util.LinkedHashMap<>();

    public SsoSecrets(SsoProperties properties, Environment environment) {
        SsoProperties.Secrets s = properties.getSecrets();
        this.pepper = requiredKey(environment, s.getPepperEnv());
        byte[] sealing = optionalKey(environment, s.getSealingKeyEnv());
        this.sealingKey = sealing != null && sealing.length == 32 ? sealing : derive(sealing != null ? sealing : pepper, "naqqa-sso/sealing");
        String kid = environment.getProperty(s.getSealingKeyIdEnv());
        this.sealingKeyId = kid != null && !kid.isBlank() ? kid.trim() : "k" + Bytes.hex(Hashes.sha256(sealingKey)).substring(0, 8);
        byte[] audit = optionalKey(environment, s.getAuditKeyEnv());
        this.auditKey = audit != null ? audit : derive(pepper, "naqqa-sso/audit");
        this.auditKeyId = keyIdOf(auditKey);
        String previous = s.getAuditPreviousKeysEnv() == null ? null : environment.getProperty(s.getAuditPreviousKeysEnv());
        if (previous != null && !previous.isBlank()) {
            for (String raw : previous.split(",")) {
                if (!raw.isBlank()) {
                    byte[] old = decode(raw.trim(), s.getAuditPreviousKeysEnv());
                    auditRing.put(keyIdOf(old), old);
                }
            }
        }
        auditRing.put(auditKeyId, auditKey);
    }

    public static byte[] optionalKey(Environment environment, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String raw = environment.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return decode(raw.trim(), name);
    }

    public static byte[] requiredKey(Environment environment, String name) {
        byte[] key = optionalKey(environment, name);
        if (key == null) {
            throw new IllegalStateException("naqqa.sso requires a secret in " + name);
        }
        return key;
    }

    static byte[] decode(String raw, String name) {
        byte[] decoded = null;
        try {
            decoded = Base64.getUrlDecoder().decode(raw.replace('+', '-').replace('/', '_').replace("=", ""));
        } catch (IllegalArgumentException ignored) {
            decoded = null;
        }
        if (decoded != null && decoded.length >= MIN_KEY_BYTES) {
            return decoded;
        }
        byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
        if (utf8.length >= MIN_KEY_BYTES) {
            return utf8;
        }
        throw new IllegalStateException(name + " must hold at least " + MIN_KEY_BYTES + " bytes");
    }

    public static byte[] derive(byte[] root, String purpose) {
        return Hashes.hkdfSha256(root, Bytes.utf8("naqqa-sso"), Bytes.utf8(purpose), 32);
    }

    public byte[] pepper() {
        return pepper.clone();
    }

    public byte[] derived(String purpose) {
        return derive(pepper, purpose);
    }

    public byte[] sealingKey() {
        return sealingKey.clone();
    }

    public String sealingKeyId() {
        return sealingKeyId;
    }

    public static String auditKeyIdOf(byte[] key) {
        return keyIdOf(key);
    }

    private static String keyIdOf(byte[] key) {
        return "a" + Bytes.hex(Hashes.sha256(key)).substring(0, 8);
    }

    public java.util.Map<String, byte[]> auditRing() {
        java.util.Map<String, byte[]> copy = new java.util.LinkedHashMap<>();
        auditRing.forEach((k, v) -> copy.put(k, v.clone()));
        return copy;
    }

    public byte[] auditKey() {
        return auditKey.clone();
    }

    public String auditKeyId() {
        return auditKeyId;
    }
}
