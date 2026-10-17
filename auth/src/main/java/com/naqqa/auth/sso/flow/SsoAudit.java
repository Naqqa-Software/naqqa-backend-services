package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.SsoSecrets;
import com.naqqa.auth.sso.admin.SsoKeyStore;
import com.naqqa.auth.sso.store.PersistentSecretBox;
import com.naqqa.auth.sso.support.SsoPrivacy;
import com.naqqa.auth.sso.store.MongoAuditStore;
import com.naqqa.sso.audit.AuditEntry;
import com.naqqa.sso.audit.AuditEvent;
import com.naqqa.sso.audit.AuditLog;
import com.naqqa.sso.audit.AuditStore;
import com.naqqa.sso.audit.VerifyResult;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class SsoAudit {

    public static final String CHECKPOINTS = "sso_audit_checkpoints";
    public static final String ANCHORS = "sso_audit_anchors";
    static final String ANCHOR_ID = "head";
    public static final String TENANT = "default";
    static final int BATCH = 1000;

    public record Checkpoint(long seq, String head, Instant at, String keyId, String signature) {
    }

    public record Anchor(long seq, String hash, Instant at, String keyId, String signature) {
    }

    public record Verification(boolean ok, String reason, long badSeq, long checkedFrom, long checkedTo, Long checkpointSeq) {
    }

    private final MongoAuditStore store;
    private final MongoTemplate template;
    private final Clock clock;
    private volatile byte[] key;
    private final Map<String, byte[]> ring;
    private final byte[] pseudonymKey;
    private volatile String keyId;
    private final Duration retention;
    private volatile AuditLog auditLog;

    public SsoAudit(MongoAuditStore store, MongoTemplate template, SsoSecrets secrets, Clock clock, SsoProperties properties) {
        this(store, template, secrets, clock, properties, null);
    }

    @Autowired
    public SsoAudit(MongoAuditStore store, MongoTemplate template, SsoSecrets secrets, Clock clock, SsoProperties properties,
                    PersistentSecretBox sealer) {
        this.store = store;
        this.template = template;
        this.clock = clock;
        this.retention = properties.getAudit().getRetention();
        this.pseudonymKey = secrets.derived("naqqa-sso/audit-pseudonym");
        this.key = secrets.auditKey();
        this.ring = Collections.synchronizedMap(new LinkedHashMap<>(secrets.auditRing()));
        this.keyId = secrets.auditKeyId();
        for (SsoKeyStore.Stored k : SsoKeyStore.load(template, sealer, SsoKeyStore.AUDIT)) {
            ring.put(k.kid(), k.secret());
            this.key = k.secret();
            this.keyId = k.kid();
        }
        this.auditLog = build();
    }

    public synchronized void rotateKey(String newKeyId, byte[] newKey) {
        auditLog.rotate(newKeyId, newKey);
        ring.put(newKeyId, newKey.clone());
        this.key = newKey.clone();
        this.keyId = newKeyId;
    }

    public synchronized boolean adoptKey(String newKeyId, byte[] newKey) {
        if (ring.containsKey(newKeyId)) {
            return false;
        }
        ring.put(newKeyId, newKey.clone());
        this.key = newKey.clone();
        this.keyId = newKeyId;
        this.auditLog = build();
        return true;
    }

    public String activeKeyId() {
        return keyId;
    }

    public List<String> keyIds() {
        synchronized (ring) {
            return new ArrayList<>(ring.keySet());
        }
    }

    private AuditLog build() {
        AuditStore tail = new AuditStore() {
            @Override
            public void append(AuditEntry entry) {
                store.append(entry);
            }

            @Override
            public List<AuditEntry> all() {
                return store.last().map(List::of).orElse(List.of());
            }
        };
        Clock millis = Clock.tick(clock, Duration.ofMillis(1));
        Optional<AuditEntry> last = store.last();
        String lastKid = last.map(AuditEntry::keyId).orElse(null);
        boolean rotatedToActive = last.map(e -> AuditLog.ROTATE_ACTION.equals(e.event().action())
                && keyId.equals(e.event().attributes().get(AuditLog.ROTATE_ATTRIBUTE))).orElse(false);
        if (lastKid != null && !lastKid.equals(keyId) && ring.containsKey(lastKid) && !rotatedToActive) {
            Map<String, byte[]> before;
            synchronized (ring) {
                before = new java.util.LinkedHashMap<>(ring);
            }
            before.remove(keyId);
            AuditLog log = new AuditLog(tail, before, lastKid, millis);
            log.rotate(keyId, key);
            return log;
        }
        synchronized (ring) {
            return new AuditLog(tail, new LinkedHashMap<>(ring), keyId, millis);
        }
    }

    public AuditEntry record(String action, String actor, String target, String outcome, Map<String, String> attributes) {
        return record(action, actor, target, outcome, attributes, currentIp());
    }

    public synchronized AuditEntry record(String action, String actor, String target, String outcome, Map<String, String> attributes, String ip) {
        Map<String, String> attrs = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((k, v) -> {
                if (k != null && v != null) {
                    attrs.put(k, v.length() > 256 ? v.substring(0, 256) : v);
                }
            });
        }
        AuditEvent event = new AuditEvent(clock.instant().truncatedTo(ChronoUnit.MILLIS), TENANT, pseudonymize(actor), action,
                pseudonymize(target), outcome, SsoPrivacy.truncateIp(ip), attrs);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return auditLog.append(event);
            } catch (DuplicateKeyException e) {
                auditLog = build();
            } catch (RuntimeException e) {
                log.error("audit append failed for action {}", action);
                return null;
            }
        }
        log.error("audit append gave up for action {}", action);
        return null;
    }

    public String pseudonym(String userId) {
        return "u~" + Bytes.b64url(Hashes.hmacSha256(pseudonymKey, Bytes.utf8("audit-user|" + userId))).substring(0, 22);
    }

    private String pseudonymize(String ref) {
        if (ref == null || ref.isEmpty() || ref.length() > 19) {
            return ref;
        }
        for (int i = 0; i < ref.length(); i++) {
            if (ref.charAt(i) < '0' || ref.charAt(i) > '9') {
                return ref;
            }
        }
        return pseudonym(ref);
    }

    private static String currentIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }

    private String sign(long seq, String head, Instant at) {
        return sign(keyId, seq, head, at);
    }

    private String sign(String kid, long seq, String head, Instant at) {
        byte[] k = ring.get(kid);
        if (k == null) {
            return "";
        }
        return Bytes.b64url(Hashes.hmacSha256(k, Bytes.utf8("checkpoint|" + kid + "|" + seq + "|" + head + "|" + at.toEpochMilli())));
    }

    @Scheduled(initialDelayString = "${naqqa.sso.audit.checkpoint-interval:PT5M}", fixedDelayString = "${naqqa.sso.audit.checkpoint-interval:PT5M}")
    public void scheduledCheckpoint() {
        try {
            checkpoint();
        } catch (RuntimeException e) {
            log.warn("audit checkpoint failed");
        }
    }

    public synchronized Optional<Checkpoint> checkpoint() {
        Optional<AuditEntry> last = store.last();
        if (last.isEmpty()) {
            return Optional.empty();
        }
        Optional<Checkpoint> latest = latestCheckpoint();
        AuditEntry e = last.get();
        if (latest.isPresent() && latest.get().seq() >= e.seq()) {
            return latest;
        }
        Instant at = clock.instant();
        Checkpoint cp = new Checkpoint(e.seq(), e.hash(), at, keyId, sign(e.seq(), e.hash(), at));
        try {
            template.insert(new Document("_id", cp.seq()).append("head", cp.head()).append("at", Date.from(at))
                    .append("keyId", cp.keyId()).append("signature", cp.signature()), CHECKPOINTS);
        } catch (DuplicateKeyException ignored) {
            return latestCheckpoint();
        }
        return Optional.of(cp);
    }

    public Optional<Checkpoint> latestCheckpoint() {
        Document d = template.findOne(new Query().with(Sort.by(Sort.Direction.DESC, "_id")).limit(1), Document.class, CHECKPOINTS);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new Checkpoint(((Number) d.get("_id")).longValue(), d.getString("head"), d.getDate("at").toInstant(),
                d.getString("keyId"), d.getString("signature")));
    }

    public List<Checkpoint> checkpoints() {
        List<Checkpoint> out = new ArrayList<>();
        for (Document d : template.find(new Query().with(Sort.by(Sort.Direction.ASC, "_id")), Document.class, CHECKPOINTS)) {
            out.add(new Checkpoint(((Number) d.get("_id")).longValue(), d.getString("head"), d.getDate("at").toInstant(),
                    d.getString("keyId"), d.getString("signature")));
        }
        return out;
    }

    private String signAnchor(long seq, String hash, Instant at, String anchorKeyId) {
        byte[] k = ring.get(anchorKeyId);
        if (k == null) {
            return "";
        }
        return Bytes.b64url(Hashes.hmacSha256(k, Bytes.utf8("purge|" + anchorKeyId + "|" + seq + "|" + hash + "|" + at.toEpochMilli())));
    }

    public Optional<Anchor> anchor() {
        Document d = template.findById(ANCHOR_ID, Document.class, ANCHORS);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new Anchor(((Number) d.get("seq")).longValue(), d.getString("hash"), d.getDate("at").toInstant(),
                d.getString("keyId"), d.getString("signature")));
    }

    @Scheduled(initialDelayString = "${naqqa.sso.audit.purge-interval:PT1H}", fixedDelayString = "${naqqa.sso.audit.purge-interval:PT1H}")
    public void scheduledPurge() {
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.warn("audit purge failed");
        }
    }

    public synchronized long purgeExpired() {
        Optional<AuditEntry> last = store.last();
        if (last.isEmpty()) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(retention);
        long firstKept = store.firstAtOrAfter(cutoff).map(AuditEntry::seq).orElse(last.get().seq());
        long boundary = firstKept - 1;
        long already = anchor().map(Anchor::seq).orElse(-1L);
        if (boundary <= already) {
            return already >= 0 ? store.deleteUpTo(already) : 0;
        }
        Optional<AuditEntry> edge = store.find(boundary);
        if (edge.isEmpty()) {
            return 0;
        }
        Instant at = clock.instant();
        String signature = signAnchor(boundary, edge.get().hash(), at, keyId);
        template.save(new Document("_id", ANCHOR_ID).append("seq", boundary).append("hash", edge.get().hash()).append("at", Date.from(at))
                .append("keyId", keyId).append("signature", signature), ANCHORS);
        long deleted = store.deleteUpTo(boundary);
        template.remove(new Query(Criteria.where("_id").lte(boundary)), CHECKPOINTS);
        record("audit.purge", "system", null, "success", Map.of("purgedTo", Long.toString(boundary), "count", Long.toString(deleted),
                "retention", retention.toString()), null);
        return deleted;
    }

    public record Export(String lines, long count, Long firstSeq, Long lastSeq, Anchor anchor, Verification verification) {
    }

    public Export export(long fromSeq, int limit, boolean full) {
        Optional<Anchor> anchor = anchor();
        long start = Math.max(fromSeq, anchor.map(a -> a.seq() + 1).orElse(0L));
        List<AuditEntry> entries = store.range(start, Math.max(1, limit));
        Verification verification = verify(full);
        Long first = entries.isEmpty() ? null : entries.get(0).seq();
        Long lastSeq = entries.isEmpty() ? null : entries.get(entries.size() - 1).seq();
        return new Export(AuditLog.exportJsonLines(entries), entries.size(), first, lastSeq, anchor.orElse(null), verification);
    }

    public Verification verify(boolean full) {
        Long cpSeq = null;
        long from = 0;
        String prev = AuditLog.GENESIS;
        Anchor anchor = anchor().orElse(null);
        if (anchor != null) {
            if (!ring.containsKey(anchor.keyId()) || !Bytes.constantTimeEquals(signAnchor(anchor.seq(), anchor.hash(), anchor.at(), anchor.keyId()), anchor.signature())) {
                return new Verification(false, "anchor_signature_invalid", anchor.seq(), 0, -1, null);
            }
            from = anchor.seq() + 1;
            prev = anchor.hash();
        }
        for (Checkpoint cp : checkpoints()) {
            if (anchor != null && cp.seq() <= anchor.seq()) {
                continue;
            }
            if (!ring.containsKey(cp.keyId()) || !Bytes.constantTimeEquals(sign(cp.keyId(), cp.seq(), cp.head(), cp.at()), cp.signature())) {
                return new Verification(false, "checkpoint_signature_invalid", cp.seq(), 0, -1, cp.seq());
            }
            Optional<AuditEntry> anchored = store.find(cp.seq());
            if (anchored.isEmpty()) {
                return new Verification(false, "truncated", cp.seq(), 0, -1, cp.seq());
            }
            if (!Bytes.constantTimeEquals(anchored.get().hash(), cp.head())) {
                return new Verification(false, "checkpoint_mismatch", cp.seq(), 0, -1, cp.seq());
            }
            cpSeq = cp.seq();
        }
        if (!full && cpSeq != null) {
            AuditEntry cpEntry = store.find(cpSeq).orElseThrow();
            if (!verifySingle(cpEntry)) {
                return new Verification(false, "signature_mismatch", cpSeq, cpSeq, cpSeq, cpSeq);
            }
            from = cpSeq + 1;
            prev = cpEntry.hash();
        }
        long checkedFrom = from;
        long next = from;
        while (true) {
            List<AuditEntry> batch = store.range(next, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            VerifyResult r = auditLog.verify(batch, next, prev, null);
            if (!r.ok()) {
                return new Verification(false, r.reason(), r.badSeq(), checkedFrom, next - 1, cpSeq);
            }
            AuditEntry last = batch.get(batch.size() - 1);
            prev = last.hash();
            next = last.seq() + 1;
            if (batch.size() < BATCH) {
                break;
            }
        }
        Optional<AuditEntry> tail = store.last();
        if (tail.isPresent() && tail.get().seq() >= next) {
            return new Verification(false, "seq_gap", next, checkedFrom, next - 1, cpSeq);
        }
        return new Verification(true, null, -1, checkedFrom, next - 1, cpSeq);
    }

    private boolean verifySingle(AuditEntry entry) {
        return auditLog.verify(List.of(entry), entry.seq(), entry.prevHash(), entry.hash()).ok();
    }

    public String head() {
        return auditLog.head();
    }
}
