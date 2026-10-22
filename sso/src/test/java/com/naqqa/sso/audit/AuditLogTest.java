package com.naqqa.sso.audit;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    private final Map<String, byte[]> keys = Map.of("k1", Bytes.random(32), "k2", Bytes.random(32));
    private final InMemoryAuditStore store = new InMemoryAuditStore();
    private final AuditLog log = new AuditLog(store, keys, "k1");

    private AuditEvent ev(int i) {
        return new AuditEvent(Instant.parse("2026-06-01T10:00:00Z").plusSeconds(i), "t1", "user" + i, "login", "acct" + i, "success", "10.0.0." + i, Map.of("amr", "pwd"));
    }

    private List<AuditEntry> fill(int n) {
        for (int i = 0; i < n; i++) {
            log.append(ev(i));
        }
        return log.entries();
    }

    @Test
    @DisplayName("SSO-T-audit-001 entries chain prev hash from genesis")
    void chain() {
        List<AuditEntry> e = fill(3);
        assertEquals(AuditLog.GENESIS, e.get(0).prevHash());
        assertEquals(e.get(0).hash(), e.get(1).prevHash());
        assertEquals(e.get(1).hash(), e.get(2).prevHash());
        assertEquals(e.get(2).hash(), log.head());
    }

    @Test
    @DisplayName("SSO-T-audit-002 an untouched chain verifies")
    void verifyGood() {
        assertTrue(log.verify(fill(20)).ok());
        assertTrue(log.verify(List.of()).ok());
    }

    @Test
    @DisplayName("SSO-T-audit-003 editing an event field is detected at that entry")
    void edit() {
        List<AuditEntry> e = new ArrayList<>(fill(5));
        AuditEntry orig = e.get(2);
        AuditEvent o = orig.event();
        e.set(2, new AuditEntry(orig.seq(), new AuditEvent(o.time(), o.tenantId(), o.actor(), o.action(), o.target(), "failure", o.ip(), o.attributes()), orig.prevHash(), orig.hash(), orig.keyId(), orig.signature()));
        VerifyResult r = log.verify(e);
        assertFalse(r.ok());
        assertEquals(2, r.badSeq());
        assertEquals("hash_mismatch", r.reason());
    }

    @Test
    @DisplayName("SSO-T-audit-004 editing an event and recomputing the hash is still caught by the signature")
    void editRehash() {
        List<AuditEntry> e = new ArrayList<>(fill(3));
        AuditEntry orig = e.get(1);
        AuditEvent o = orig.event();
        AuditEvent forged = new AuditEvent(o.time(), o.tenantId(), "mallory", o.action(), o.target(), o.outcome(), o.ip(), o.attributes());
        String newHash = Bytes.hex(com.naqqa.sso.util.Hashes.sha256(Bytes.utf8(orig.seq() + "|" + orig.prevHash() + "|" + forged.canonical())));
        e.set(1, new AuditEntry(orig.seq(), forged, orig.prevHash(), newHash, orig.keyId(), orig.signature()));
        VerifyResult r = log.verify(e);
        assertFalse(r.ok());
        assertEquals("signature_mismatch", r.reason());
    }

    @Test
    @DisplayName("SSO-T-audit-005 deleting a middle entry is detected")
    void delete() {
        List<AuditEntry> e = new ArrayList<>(fill(5));
        e.remove(2);
        VerifyResult r = log.verify(e);
        assertFalse(r.ok());
        assertEquals("seq_gap", r.reason());
    }

    @Test
    @DisplayName("SSO-T-audit-006 deleting the first entry is detected")
    void deleteFirst() {
        List<AuditEntry> e = new ArrayList<>(fill(4));
        e.remove(0);
        assertFalse(log.verify(e).ok());
    }

    @Test
    @DisplayName("SSO-T-audit-007 truncating the tail is detected with an anchored head")
    void truncateTail() {
        List<AuditEntry> e = new ArrayList<>(fill(5));
        String head = log.head();
        e.remove(4);
        assertTrue(log.verify(e).ok());
        VerifyResult r = log.verify(e, 0, AuditLog.GENESIS, head);
        assertFalse(r.ok());
        assertEquals("head_mismatch", r.reason());
    }

    @Test
    @DisplayName("SSO-T-audit-008 reordering entries is detected")
    void reorder() {
        List<AuditEntry> e = new ArrayList<>(fill(5));
        AuditEntry t = e.get(1);
        e.set(1, e.get(2));
        e.set(2, t);
        assertFalse(log.verify(e).ok());
    }

    @Test
    @DisplayName("SSO-T-audit-009 inserting a forged entry is detected")
    void insert() {
        List<AuditEntry> e = new ArrayList<>(fill(4));
        AuditEntry prev = e.get(1);
        AuditEvent ev = ev(99);
        String hash = Bytes.hex(com.naqqa.sso.util.Hashes.sha256(Bytes.utf8(2 + "|" + prev.hash() + "|" + ev.canonical())));
        e.add(2, new AuditEntry(2, ev, prev.hash(), hash, "k1", "forged-signature"));
        VerifyResult r = log.verify(e);
        assertFalse(r.ok());
    }

    @Test
    @DisplayName("SSO-T-audit-010 forged signature with an attacker key fails")
    void wrongKey() {
        List<AuditEntry> e = fill(2);
        AuditLog attacker = new AuditLog(new InMemoryAuditStore(), Map.of("k1", Bytes.random(32)), "k1");
        attacker.append(ev(0));
        attacker.append(ev(1));
        assertFalse(log.verify(attacker.entries()).ok());
        assertTrue(log.verify(e).ok());
    }

    @Test
    @DisplayName("SSO-T-audit-011 unknown key id is reported")
    void unknownKey() {
        List<AuditEntry> e = new ArrayList<>(fill(2));
        AuditEntry o = e.get(0);
        e.set(0, new AuditEntry(o.seq(), o.event(), o.prevHash(), o.hash(), "k9", o.signature()));
        assertEquals("unknown_key", log.verify(e).reason());
    }

    @Test
    @DisplayName("SSO-T-audit-012 key rotation: old entries verify with retained key, new entries use the new key id")
    void rotation() {
        fill(2);
        AuditLog rotated = new AuditLog(store, keys, "k2");
        rotated.append(ev(5));
        List<AuditEntry> all = rotated.entries();
        assertEquals("k1", all.get(0).keyId());
        assertEquals("k2", all.get(2).keyId());
        assertTrue(rotated.verify(all).ok());
        assertEquals(2, all.get(2).seq());
    }

    @Test
    @DisplayName("SSO-T-audit-013 a log rebuilt over an existing store continues the chain")
    void resume() {
        fill(3);
        AuditLog again = new AuditLog(store, keys, "k1");
        again.append(ev(7));
        assertTrue(again.verify(again.entries()).ok());
        assertEquals(4, again.entries().size());
    }

    @Test
    @DisplayName("SSO-T-audit-014 constructor rejects missing or short keys")
    void keyChecks() {
        assertThrows(SsoException.class, () -> new AuditLog(new InMemoryAuditStore(), keys, "nope"));
        assertThrows(SsoException.class, () -> new AuditLog(new InMemoryAuditStore(), Map.of("k", new byte[8]), "k"));
    }

    @Test
    @DisplayName("SSO-T-audit-015 canonical bytes are unambiguous across field boundaries")
    void canonicalAmbiguity() {
        AuditEvent a = new AuditEvent(Instant.EPOCH, "t", "ab", "c", "x", "o", null, Map.of());
        AuditEvent b = new AuditEvent(Instant.EPOCH, "t", "a", "bc", "x", "o", null, Map.of());
        assertNotEquals(a.canonical(), b.canonical());
        AuditEvent n = new AuditEvent(Instant.EPOCH, "t", "a", "c", "x", "o", null, Map.of());
        AuditEvent e = new AuditEvent(Instant.EPOCH, "t", "a", "c", "x", "o", "", Map.of());
        assertNotEquals(n.canonical(), e.canonical());
    }

    @Test
    @DisplayName("SSO-T-audit-016 attribute order does not change the canonical form")
    void attributeOrder() {
        AuditEvent a = new AuditEvent(Instant.EPOCH, "t", "a", "c", "x", "o", null, Map.of("a", "1", "b", "2", "c", "3"));
        AuditEvent b = new AuditEvent(Instant.EPOCH, "t", "a", "c", "x", "o", null, new java.util.TreeMap<>(Map.of("c", "3", "b", "2", "a", "1")));
        assertEquals(a.canonical(), b.canonical());
    }

    @Test
    @DisplayName("SSO-T-audit-017 JSON lines export has one object per line with escaping")
    void export() {
        log.append(new AuditEvent(Instant.EPOCH, "t1", "al\"ice", "lo\\gin", "line1\nline2", "ok\ttab", null, Map.of("k", "\u0001ctl")));
        String out = AuditLog.exportJsonLines(log.entries());
        assertEquals(1, out.split("\n").length);
        assertTrue(out.contains("\"actor\":\"al\\\"ice\""));
        assertTrue(out.contains("lo\\\\gin"));
        assertTrue(out.contains("line1\\nline2"));
        assertTrue(out.contains("ok\\ttab"));
        assertTrue(out.contains("\\u0001ctl"));
        assertTrue(out.contains("\"ip\":null"));
        assertTrue(out.endsWith("}\n"));
    }

    @Test
    @DisplayName("SSO-T-audit-018 JSON escape handles unicode line separators and null")
    void escape() {
        assertEquals("\"\\u2028\"", AuditLog.escape("\u2028"));
        assertEquals("null", AuditLog.escape(null));
        assertEquals("\"caf\u00e9\"", AuditLog.escape("caf\u00e9"));
        assertEquals("\"\\b\\f\\r\"", AuditLog.escape("\b\f\r"));
    }

    @Test
    @DisplayName("SSO-T-audit-019 export lines carry the chain fields")
    void exportFields() {
        fill(2);
        String[] lines = AuditLog.exportJsonLines(log.entries()).split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("\"prevHash\":\"" + log.entries().get(0).hash() + "\""));
        assertTrue(lines[1].contains("\"keyId\":\"k1\""));
        assertTrue(lines[0].contains("\"seq\":0"));
    }

    @Test
    @DisplayName("SSO-T-audit-020 concurrent appends keep a valid chain")
    void concurrentAppend() throws Exception {
        Thread[] ts = new Thread[6];
        for (int t = 0; t < ts.length; t++) {
            final int base = t * 100;
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 30; i++) {
                    log.append(ev(base + i));
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(180, log.entries().size());
        assertTrue(log.verify(log.entries()).ok());
    }

    @Test
    @DisplayName("SSO-T-audit-021 swapping the signature of two entries is detected")
    void swapSignatures() {
        List<AuditEntry> e = new ArrayList<>(fill(3));
        AuditEntry a = e.get(0);
        AuditEntry b = e.get(1);
        e.set(0, new AuditEntry(a.seq(), a.event(), a.prevHash(), a.hash(), a.keyId(), b.signature()));
        assertEquals("signature_mismatch", log.verify(e).reason());
    }

    private AuditLog rotating(java.time.Clock clock) {
        return new AuditLog(new InMemoryAuditStore(), Map.of("a", Bytes.random(32)), "a", clock);
    }

    private AuditEntry forge(AuditLog l, String kid, byte[] key, int i) {
        List<AuditEntry> cur = l.entries();
        AuditEntry last = cur.get(cur.size() - 1);
        AuditEvent ev = ev(i);
        long seq = last.seq() + 1;
        String hash = Bytes.hex(com.naqqa.sso.util.Hashes.sha256(Bytes.utf8(seq + "|" + last.hash() + "|" + ev.canonical())));
        String sig = Bytes.b64url(com.naqqa.sso.util.Hashes.hmacSha256(key, Bytes.utf8("audit|" + kid + "|" + seq + "|" + hash)));
        return new AuditEntry(seq, ev, last.hash(), hash, kid, sig);
    }

    @Test
    @DisplayName("SSO-T-audit-022 F-G5 chain verifies across two rotations with signed hand-over events")
    void twoRotations() {
        java.time.Clock clock = java.time.Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        byte[] ka = Bytes.random(32);
        AuditLog l = new AuditLog(new InMemoryAuditStore(), Map.of("a", ka), "a", clock);
        l.append(ev(0));
        AuditEntry r1 = l.rotate("b", Bytes.random(32));
        l.append(ev(1));
        AuditEntry r2 = l.rotate("c", Bytes.random(32));
        l.append(ev(2));
        List<AuditEntry> all = l.entries();
        assertEquals("a", r1.keyId());
        assertEquals("b", r2.keyId());
        assertEquals("c", all.get(all.size() - 1).keyId());
        assertEquals("c", l.activeKeyId());
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), r1.event().time());
        assertEquals("b", r1.event().attributes().get(AuditLog.ROTATE_ATTRIBUTE));
        assertTrue(l.verify(all).ok());
        assertTrue(l.verify(all, 0, AuditLog.GENESIS, l.head()).ok());
        assertTrue(AuditLog.exportJsonLines(all).contains("\"keyId\":\"b\""));
        assertThrows(SsoException.class, () -> l.append(r1.event()));
    }

    @Test
    @DisplayName("SSO-T-audit-023 F-G5 forged entry signed with a retired key after rotation is rejected")
    void retiredForgery() {
        byte[] ka = Bytes.random(32);
        AuditLog l = new AuditLog(new InMemoryAuditStore(), Map.of("a", ka), "a");
        l.append(ev(0));
        l.rotate("b", Bytes.random(32));
        l.append(ev(1));
        List<AuditEntry> chain = new ArrayList<>(l.entries());
        chain.add(forge(l, "a", ka, 9));
        VerifyResult r = l.verify(chain);
        assertFalse(r.ok());
        assertEquals("retired_key", r.reason());
        assertEquals(chain.size() - 1, r.badSeq());
    }

    @Test
    @DisplayName("SSO-T-audit-024 F-G5 unknown key id after rotation is rejected")
    void unknownAfterRotation() {
        AuditLog l = rotating(java.time.Clock.systemUTC());
        l.append(ev(0));
        l.rotate("b", Bytes.random(32));
        List<AuditEntry> chain = new ArrayList<>(l.entries());
        chain.add(forge(l, "zz", Bytes.random(32), 3));
        assertEquals("unknown_key", l.verify(chain).reason());
    }

    @Test
    @DisplayName("SSO-T-audit-025 F-G5 tampering is still detected across rotations")
    void tamperAcrossRotation() {
        AuditLog l = rotating(java.time.Clock.systemUTC());
        l.append(ev(0));
        l.rotate("b", Bytes.random(32));
        l.append(ev(1));
        List<AuditEntry> chain = new ArrayList<>(l.entries());
        AuditEntry o = chain.get(0);
        AuditEvent x = o.event();
        chain.set(0, new AuditEntry(o.seq(), new AuditEvent(x.time(), x.tenantId(), "mallory", x.action(), x.target(), x.outcome(), x.ip(), x.attributes()), o.prevHash(), o.hash(), o.keyId(), o.signature()));
        assertEquals("hash_mismatch", l.verify(chain).reason());
        List<AuditEntry> dropped = new ArrayList<>(l.entries());
        dropped.remove(1);
        assertFalse(l.verify(dropped).ok());
    }
}
