package com.naqqa.sso.audit;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AuditLog {
    public static final String GENESIS = "0".repeat(64);

    private final AuditStore store;
    public static final String ROTATE_ACTION = "audit.key.rotate";
    public static final String ROTATE_ATTRIBUTE = "newKid";

    private final Clock clock;
    private volatile Map<String, byte[]> keys;
    private String activeKeyId;
    private String head = GENESIS;
    private long nextSeq = 0;

    public AuditLog(AuditStore store, Map<String, byte[]> keys, String activeKeyId) {
        this(store, keys, activeKeyId, Clock.systemUTC());
    }

    public AuditLog(AuditStore store, Map<String, byte[]> keys, String activeKeyId, Clock clock) {
        if (clock == null) {
            throw new SsoException("audit_clock_missing");
        }
        this.clock = clock;
        if (!keys.containsKey(activeKeyId)) {
            throw new SsoException("audit_key_missing");
        }
        for (byte[] k : keys.values()) {
            if (k.length < 32) {
                throw new SsoException("audit_key_invalid");
            }
        }
        this.store = store;
        this.keys = Map.copyOf(keys);
        this.activeKeyId = activeKeyId;
        List<AuditEntry> existing = store.all();
        if (!existing.isEmpty()) {
            if (retiredKeys(existing).contains(activeKeyId)) {
                throw new SsoException("audit_key_retired");
            }
            AuditEntry last = existing.get(existing.size() - 1);
            head = last.hash();
            nextSeq = last.seq() + 1;
        }
    }

    private static String hashOf(long seq, String prev, AuditEvent event) {
        return Bytes.hex(Hashes.sha256(Bytes.utf8(seq + "|" + prev + "|" + event.canonical())));
    }

    private static String sign(byte[] key, long seq, String hash, String keyId) {
        return Bytes.b64url(Hashes.hmacSha256(key, Bytes.utf8("audit|" + keyId + "|" + seq + "|" + hash)));
    }

    private static Set<String> retiredKeys(List<AuditEntry> chain) {
        Set<String> retired = new HashSet<>();
        String current = null;
        for (AuditEntry e : chain) {
            if (current != null && !current.equals(e.keyId())) {
                retired.add(current);
            }
            current = e.keyId();
            if (ROTATE_ACTION.equals(e.event().action())) {
                retired.add(current);
            }
        }
        return retired;
    }

    public synchronized AuditEntry append(AuditEvent event) {
        if (ROTATE_ACTION.equals(event.action())) {
            throw new SsoException("audit_action_reserved");
        }
        return write(event);
    }

    public synchronized AuditEntry rotate(String newKeyId, byte[] newKey) {
        if (newKeyId == null || newKeyId.isEmpty() || newKey == null || newKey.length < 32) {
            throw new SsoException("audit_key_invalid");
        }
        if (keys.containsKey(newKeyId)) {
            throw new SsoException("audit_key_exists");
        }
        AuditEvent event = new AuditEvent(clock.instant(), null, "system", ROTATE_ACTION, activeKeyId, "success", null, Map.of(ROTATE_ATTRIBUTE, newKeyId));
        AuditEntry entry = write(event);
        Map<String, byte[]> next = new HashMap<>(keys);
        next.put(newKeyId, newKey.clone());
        keys = Map.copyOf(next);
        activeKeyId = newKeyId;
        return entry;
    }

    public synchronized String activeKeyId() {
        return activeKeyId;
    }

    private AuditEntry write(AuditEvent event) {
        String hash = hashOf(nextSeq, head, event);
        AuditEntry entry = new AuditEntry(nextSeq, event, head, hash, activeKeyId, sign(keys.get(activeKeyId), nextSeq, hash, activeKeyId));
        store.append(entry);
        head = hash;
        nextSeq++;
        return entry;
    }

    public synchronized String head() {
        return head;
    }

    public List<AuditEntry> entries() {
        return store.all();
    }

    public VerifyResult verify(List<AuditEntry> chain) {
        return verify(chain, 0, GENESIS, null);
    }

    public VerifyResult verify(List<AuditEntry> chain, long firstSeq, String firstPrev, String expectedHead) {
        Map<String, byte[]> ring = keys;
        String prev = firstPrev;
        long seq = firstSeq;
        Set<String> retired = new HashSet<>();
        String current = null;
        String pending = null;
        for (AuditEntry e : chain) {
            if (e.seq() != seq) {
                return VerifyResult.bad(e.seq(), "seq_gap");
            }
            if (!Bytes.constantTimeEquals(e.prevHash(), prev)) {
                return VerifyResult.bad(e.seq(), "prev_mismatch");
            }
            if (!Bytes.constantTimeEquals(e.hash(), hashOf(e.seq(), e.prevHash(), e.event()))) {
                return VerifyResult.bad(e.seq(), "hash_mismatch");
            }
            byte[] key = e.keyId() == null ? null : ring.get(e.keyId());
            if (key == null) {
                return VerifyResult.bad(e.seq(), "unknown_key");
            }
            if (!Bytes.constantTimeEquals(e.signature(), sign(key, e.seq(), e.hash(), e.keyId()))) {
                return VerifyResult.bad(e.seq(), "signature_mismatch");
            }
            if (retired.contains(e.keyId())) {
                return VerifyResult.bad(e.seq(), "retired_key");
            }
            if (pending != null) {
                if (!pending.equals(e.keyId())) {
                    return VerifyResult.bad(e.seq(), "rotation_mismatch");
                }
                pending = null;
            } else if (current != null && !current.equals(e.keyId())) {
                retired.add(current);
            }
            current = e.keyId();
            if (ROTATE_ACTION.equals(e.event().action())) {
                String next = e.event().attributes().get(ROTATE_ATTRIBUTE);
                if (next == null || !ring.containsKey(next)) {
                    return VerifyResult.bad(e.seq(), "unknown_key");
                }
                if (retired.contains(next) || next.equals(current)) {
                    return VerifyResult.bad(e.seq(), "rotation_mismatch");
                }
                retired.add(current);
                pending = next;
            }
            prev = e.hash();
            seq++;
        }
        if (expectedHead != null && !Bytes.constantTimeEquals(prev, expectedHead)) {
            return VerifyResult.bad(seq, "head_mismatch");
        }
        return VerifyResult.good();
    }

    public static String escape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7F || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String exportJsonLines(List<AuditEntry> chain) {
        StringBuilder out = new StringBuilder();
        for (AuditEntry e : chain) {
            AuditEvent ev = e.event();
            out.append("{\"seq\":").append(e.seq())
                    .append(",\"time\":").append(escape(ev.time().toString()))
                    .append(",\"tenantId\":").append(escape(ev.tenantId()))
                    .append(",\"actor\":").append(escape(ev.actor()))
                    .append(",\"action\":").append(escape(ev.action()))
                    .append(",\"target\":").append(escape(ev.target()))
                    .append(",\"outcome\":").append(escape(ev.outcome()))
                    .append(",\"ip\":").append(escape(ev.ip()))
                    .append(",\"attributes\":{");
            boolean first = true;
            for (Map.Entry<String, String> a : new java.util.TreeMap<>(ev.attributes()).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(escape(a.getKey())).append(':').append(escape(a.getValue()));
            }
            out.append("},\"prevHash\":").append(escape(e.prevHash()))
                    .append(",\"hash\":").append(escape(e.hash()))
                    .append(",\"keyId\":").append(escape(e.keyId()))
                    .append(",\"signature\":").append(escape(e.signature()))
                    .append("}\n");
        }
        return out.toString();
    }
}
