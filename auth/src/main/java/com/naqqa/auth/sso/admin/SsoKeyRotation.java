package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.oidc.OidcSigningKeys;
import com.naqqa.auth.sso.store.PersistentSecretBox;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.crypto.KeyRing;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.crypto.SigningAlgorithm;
import com.naqqa.sso.crypto.SigningKey;
import com.naqqa.sso.util.Bytes;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class SsoKeyRotation {

    public static final Duration DEFAULT_GRACE = Duration.ofHours(24);
    public static final Duration MAX_GRACE = Duration.ofDays(7);

    public record Rotation(String ring, String kid, String previousKid, Instant previousNotAfter) {
    }

    private final MongoTemplate template;
    private final PersistentSecretBox sealer;
    private final Clock clock;
    private final KeyRing accessRing;
    private final ObjectProvider<OidcSigningKeys> oidcKeys;
    private final SsoAudit audit;
    private final Map<String, Set<String>> applied = new HashMap<>();

    public SsoKeyRotation(MongoTemplate template, PersistentSecretBox sealer, Clock clock, KeyRing accessRing,
                          ObjectProvider<OidcSigningKeys> oidcKeys, SsoAudit audit) {
        this.template = template;
        this.sealer = sealer;
        this.clock = clock;
        this.accessRing = accessRing;
        this.oidcKeys = oidcKeys;
        this.audit = audit;
        sync();
    }

    KeyRing ring(String name) {
        if (SsoKeyStore.ACCESS.equals(name)) {
            return accessRing;
        }
        if (SsoKeyStore.OIDC.equals(name)) {
            OidcSigningKeys keys = oidcKeys.getIfAvailable();
            return keys == null ? null : keys.ring();
        }
        return null;
    }

    private Set<String> applied(String name, KeyRing ring) {
        return applied.computeIfAbsent(name, k -> {
            Set<String> s = new HashSet<>();
            ring.entries().forEach(e -> s.add(e.key().kid()));
            return s;
        });
    }

    @Scheduled(initialDelayString = "${naqqa.sso.keys.sync-interval:PT1M}", fixedDelayString = "${naqqa.sso.keys.sync-interval:PT1M}")
    public void scheduledSync() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.warn("signing key sync failed");
        }
    }

    public synchronized void sync() {
        Instant now = clock.instant();
        for (String name : List.of(SsoKeyStore.ACCESS, SsoKeyStore.OIDC)) {
            KeyRing ring = ring(name);
            if (ring == null) {
                continue;
            }
            Set<String> done = applied(name, ring);
            for (SsoKeyStore.Stored s : SsoKeyStore.load(template, sealer, name)) {
                try {
                    if (done.contains(s.kid())) {
                        continue;
                    }
                    SigningKey key = Pem.signingKey(new String(s.secret(), StandardCharsets.US_ASCII), s.publicPem());
                    Duration grace = s.previousNotAfter() == null || !s.previousNotAfter().isAfter(now) ? Duration.ZERO
                            : Duration.between(now, s.previousNotAfter());
                    ring.rotate(key, grace);
                    done.add(s.kid());
                } finally {
                    Bytes.wipe(s.secret());
                }
            }
            ring.prune();
        }
        for (SsoKeyStore.Stored s : SsoKeyStore.load(template, sealer, SsoKeyStore.AUDIT)) {
            try {
                audit.adoptKey(s.kid(), s.secret());
            } finally {
                Bytes.wipe(s.secret());
            }
        }
    }

    public synchronized Rotation rotate(String name, Duration grace) {
        sync();
        Instant now = clock.instant();
        if (SsoKeyStore.AUDIT.equals(name)) {
            byte[] key = Bytes.random(32);
            String kid = SsoSecrets.auditKeyIdOf(key);
            String previous = audit.activeKeyId();
            try {
                SsoKeyStore.insert(template, sealer, name, kid, "HS256", key, null, now, previous, null);
                audit.rotateKey(kid, key);
            } finally {
                Bytes.wipe(key);
            }
            return new Rotation(name, kid, previous, null);
        }
        KeyRing ring = ring(name);
        if (ring == null) {
            if (SsoKeyStore.OIDC.equals(name)) {
                throw SsoApiException.notConfigured("OIDC provider");
            }
            throw SsoApiException.notFound("NOT_FOUND", "Key ring not found");
        }
        SigningKey current = ring.active();
        SigningAlgorithm alg = current.alg();
        SigningKey next = alg == SigningAlgorithm.RS256 ? SigningKey.generateRsa(2048) : SigningKey.generate(alg);
        Instant notAfter = now.plus(grace);
        byte[] pem = Pem.encodePrivateKey(next.privateKey()).getBytes(StandardCharsets.US_ASCII);
        try {
            SsoKeyStore.insert(template, sealer, name, next.kid(), alg.name(), pem, Pem.encodePublicKey(next.publicKey()), now,
                    current.kid(), notAfter);
        } finally {
            Bytes.wipe(pem);
        }
        ring.rotate(next, grace);
        applied(name, ring).add(next.kid());
        return new Rotation(name, next.kid(), current.kid(), notAfter);
    }

    public List<Map<String, Object>> view() {
        Instant now = clock.instant();
        List<Map<String, Object>> rings = new ArrayList<>();
        for (String name : List.of(SsoKeyStore.ACCESS, SsoKeyStore.OIDC)) {
            KeyRing ring = ring(name);
            if (ring == null) {
                continue;
            }
            List<Map<String, Object>> keys = new ArrayList<>();
            for (KeyRing.Entry e : ring.entries()) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("kid", e.key().kid());
                k.put("alg", e.key().alg().name());
                k.put("status", e.active() ? "active" : e.usableAt(now) ? "retiring" : "retired");
                k.put("notAfter", e.notAfter());
                Document meta = SsoKeyStore.meta(template, name, e.key().kid());
                k.put("createdAt", meta == null ? null : meta.getDate("createdAt") == null ? null : meta.getDate("createdAt").toInstant());
                keys.add(k);
            }
            rings.add(Map.of("ring", name, "keys", keys));
        }
        List<Map<String, Object>> auditKeys = new ArrayList<>();
        String active = audit.activeKeyId();
        for (String kid : audit.keyIds()) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("kid", kid);
            k.put("alg", "HS256");
            k.put("status", kid.equals(active) ? "active" : "retiring");
            k.put("notAfter", null);
            Document meta = SsoKeyStore.meta(template, SsoKeyStore.AUDIT, kid);
            k.put("createdAt", meta == null ? null : meta.getDate("createdAt") == null ? null : meta.getDate("createdAt").toInstant());
            auditKeys.add(k);
        }
        rings.add(Map.of("ring", SsoKeyStore.AUDIT, "keys", auditKeys));
        return rings;
    }
}
