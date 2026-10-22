package com.naqqa.sso.password;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordTest {
    private static final byte[] PEPPER = Bytes.random(32);
    private static final PasswordHasher FAST = new PasswordHasher(PEPPER, PasswordHasher.MIN_ITERATIONS);
    private static final String PW = "correct horse battery staple";

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    @Test
    @DisplayName("SSO-T-password-001 default hash is PHC pbkdf2-sha512 with 210000 iterations and verifies")
    void defaultHash() {
        PasswordHasher h = new PasswordHasher(PEPPER);
        String enc = h.hash(PW);
        assertTrue(enc.matches("\\$pbkdf2-sha512\\$v=1\\$i=210000\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{86}"));
        assertTrue(h.verify(PW, enc));
        assertFalse(h.verify(PW + "x", enc));
        assertEquals(210_000, h.iterations());
    }

    @Test
    @DisplayName("SSO-T-password-002 salts are random per hash")
    void uniqueSalts() {
        String a = FAST.hash(PW);
        String b = FAST.hash(PW);
        assertNotEquals(a, b);
        assertEquals(16, PasswordHasher.parse(a).salt().length);
        assertEquals(64, PasswordHasher.parse(a).hash().length);
    }

    @Test
    @DisplayName("SSO-T-password-003 pepper is mixed in: a different pepper fails verification")
    void pepper() {
        String enc = FAST.hash(PW);
        PasswordHasher other = new PasswordHasher(Bytes.random(32), PasswordHasher.MIN_ITERATIONS);
        assertFalse(other.verify(PW, enc));
    }

    @Test
    @DisplayName("SSO-T-password-004 needsRehash when stored iterations are below configuration or format is foreign")
    void needsRehash() {
        String enc = FAST.hash(PW);
        assertFalse(FAST.needsRehash(enc));
        PasswordHasher stronger = new PasswordHasher(PEPPER, 150_000);
        assertTrue(stronger.needsRehash(enc));
        assertTrue(stronger.verify(PW, enc));
        assertTrue(FAST.needsRehash("$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"));
    }

    @Test
    @DisplayName("SSO-T-password-005 configuration floor: iterations >= 100000 and pepper >= 32 bytes")
    void configFloor() {
        assertEquals("password.iterations_invalid", code(() -> new PasswordHasher(PEPPER, 99_999)));
        assertEquals("password.pepper_too_short", code(() -> new PasswordHasher(new byte[16])));
        assertEquals("password.pepper_too_short", code(() -> new PasswordHasher(null)));
    }

    @Test
    @DisplayName("SSO-T-password-006 PHC parser rejects tampered or downgraded strings")
    void parseStrict() {
        String enc = FAST.hash(PW);
        String[] p = enc.split("\\$");
        String salt = p[4];
        String hash = p[5];
        for (String bad : new String[]{
                "$pbkdf2-sha256$v=1$i=100000$" + salt + "$" + hash,
                "$pbkdf2-sha512$v=2$i=100000$" + salt + "$" + hash,
                "$pbkdf2-sha512$v=1$i=0100000$" + salt + "$" + hash,
                "$pbkdf2-sha512$v=1$i=1000$" + salt + "$" + hash,
                "$pbkdf2-sha512$v=1$i=99999999$" + salt + "$" + hash,
                "$pbkdf2-sha512$v=1$i=100000$" + salt.substring(1) + "$" + hash,
                "$pbkdf2-sha512$v=1$i=100000$" + salt + "==$" + hash,
                "$pbkdf2-sha512$v=1$i=100000$" + salt + "$" + hash + "$",
                "pbkdf2-sha512$v=1$i=100000$" + salt + "$" + hash}) {
            assertEquals("password.hash_format", code(() -> PasswordHasher.parse(bad)));
        }
    }

    @Test
    @DisplayName("SSO-T-password-007 verify against malformed stored hash returns false without throwing")
    void verifyMalformed() {
        assertFalse(FAST.verify(PW, "garbage"));
        assertFalse(FAST.verify(PW, null));
        assertFalse(FAST.verify(null, FAST.hash(PW)));
        assertFalse(FAST.verify("", FAST.hash(PW)));
    }

    @Test
    @DisplayName("SSO-T-password-008 unknown-user dummy verify returns false with comparable timing")
    void dummyTiming() {
        String enc = FAST.hash(PW);
        long known = 0;
        long unknown = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            FAST.verify("wrong password value", enc);
            long t1 = System.nanoTime();
            assertFalse(FAST.verifyUnknownUser("wrong password value"));
            long t2 = System.nanoTime();
            known += t1 - t0;
            unknown += t2 - t1;
        }
        double ratio = (double) unknown / known;
        assertTrue(ratio > 0.33 && ratio < 3.0, "ratio " + ratio);
    }

    @Test
    @DisplayName("SSO-T-password-009 NFKC normalization makes equivalent Unicode forms verify")
    void nfkc() {
        String composed = "café au lait pour moi";
        String decomposed = "café au lait pour moi";
        assertTrue(FAST.verify(decomposed, FAST.hash(composed)));
        assertTrue(FAST.verify("ﬁsh and chips forever", FAST.hash("fish and chips forever")));
    }

    @Test
    @DisplayName("SSO-T-password-010 empty and overlong passwords are refused by the hasher")
    void hasherBounds() {
        assertEquals("password.empty", code(() -> FAST.hash("")));
        assertEquals("password.empty", code(() -> FAST.hash(null)));
        assertEquals("password.too_long", code(() -> FAST.hash("a".repeat(1025))));
        assertFalse(FAST.verify("a".repeat(5000), FAST.hash(PW)));
    }

    @Test
    @DisplayName("SSO-T-password-011 policy: minimum length 12 by default")
    void minLength() {
        PasswordPolicy p = PasswordPolicy.defaults();
        assertEquals(List.of("password.too_short"), p.evaluate("tiger lamp9", List.of()).violations());
        assertTrue(p.evaluate("tiger lamp 9", List.of()).accepted());
        assertEquals(12, p.minLength());
    }

    @Test
    @DisplayName("SSO-T-password-012 policy: maximum length 128")
    void maxLength() {
        PasswordPolicy p = PasswordPolicy.defaults();
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 129) {
            sb.append("river stone ");
        }
        String pw = sb.substring(0, 129);
        assertTrue(p.evaluate(pw, List.of()).violations().contains("password.too_long"));
        assertTrue(p.evaluate(pw.substring(0, 128), List.of()).accepted());
    }

    @Test
    @DisplayName("SSO-T-password-013 policy: length counts code points after NFKC")
    void codePoints() {
        PasswordPolicy p = PasswordPolicy.defaults();
        String emoji = "😀😁😂😃😄😅";
        assertTrue(p.evaluate(emoji + "abcde", List.of()).violations().contains("password.too_short"));
        assertTrue(p.evaluate(emoji + "abxyqrz", List.of()).accepted());
    }

    @Test
    @DisplayName("SSO-T-password-014 policy: no composition rules, lowercase passphrase accepted")
    void noComposition() {
        assertTrue(PasswordPolicy.defaults().evaluate("purple monkey dishwasher", List.of()).accepted());
    }

    @Test
    @DisplayName("SSO-T-password-015 policy: block list matches with case folding and decoration stripped")
    void blockList() {
        PasswordPolicy p = PasswordPolicy.builder().blockWords(List.of("NaqqaSecure")).build();
        assertTrue(p.evaluate("Password1234!", List.of()).violations().contains("password.blocked"));
        assertTrue(p.evaluate("QWERTYUIOP", List.of()).violations().contains("password.blocked"));
        assertTrue(p.evaluate("2024naqqasecure!!", List.of()).violations().contains("password.blocked"));
        assertFalse(p.evaluate("my password is long enough", List.of()).violations().contains("password.blocked"));
    }

    @Test
    @DisplayName("SSO-T-password-016 policy: context words from email and name are rejected")
    void context() {
        PasswordPolicy p = PasswordPolicy.defaults();
        List<String> ctx = List.of("alexandru.popescu@naqqa.com", "Alexandru Popescu");
        assertTrue(p.evaluate("ilovepopescu2024", ctx).violations().contains("password.context"));
        assertTrue(p.evaluate("Alexandru.Popescu@naqqa.com", ctx).violations().contains("password.context"));
        assertTrue(p.evaluate("winter garden lamp", ctx).accepted());
    }

    @Test
    @DisplayName("SSO-T-password-017 policy: repetitive and sequential passwords are rejected")
    void repetitive() {
        PasswordPolicy p = PasswordPolicy.defaults();
        for (String s : new String[]{"aaaaaaaaaaaaa", "abababababab", "abcdefghijklmn", "0123456789", "zyxwvutsrqpo"}) {
            assertTrue(p.evaluate(s, List.of()).violations().contains("password.repetitive"), s);
        }
    }

    @Test
    @DisplayName("SSO-T-password-018 policy: enforce throws the first violation code; control chars invalid")
    void enforce() {
        PasswordPolicy p = PasswordPolicy.defaults();
        assertEquals("password.too_short", code(() -> p.enforce("short", List.of())));
        assertEquals("password.empty", code(() -> p.enforce("", List.of())));
        assertTrue(p.evaluate("line one\u0007bell here", List.of()).violations().contains("password.invalid_chars"));
        assertEquals("password.policy_invalid", code(() -> PasswordPolicy.builder().minLength(6)));
    }

    @Test
    @DisplayName("SSO-T-password-019 policy integrates the breached-password filter")
    void breachedInPolicy() {
        BreachedPasswordFilter f = BreachedPasswordFilter.create(10, 100, 0.001);
        f.addPassword("tr0ub4dor&3 horse");
        PasswordPolicy p = PasswordPolicy.builder().breachedFilter(f).build();
        assertTrue(p.evaluate("tr0ub4dor&3 horse", List.of()).violations().contains("password.breached"));
        assertTrue(p.evaluate("tr0ub4dor&3 horses", List.of()).accepted());
    }

    @Test
    @DisplayName("SSO-T-password-020 bloom filter from SHA-1 prefix stream has no false negatives")
    void bloomNoFalseNegatives() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int i = 0; i < 2000; i++) {
            raw.write(BreachedPasswordFilter.sha1Prefix("leaked-" + i, 10));
        }
        BreachedPasswordFilter f = BreachedPasswordFilter.fromSha1Prefixes(new ByteArrayInputStream(raw.toByteArray()), 10, 2000, 0.01);
        assertEquals(2000, f.entries());
        for (int i = 0; i < 2000; i++) {
            assertTrue(f.mightBeBreached("leaked-" + i));
        }
    }

    @Test
    @DisplayName("SSO-T-password-021 bloom filter false-positive rate stays near the configured 1 percent")
    void bloomFalsePositiveRate() {
        BreachedPasswordFilter f = BreachedPasswordFilter.create(10, 20_000, 0.01);
        for (int i = 0; i < 20_000; i++) {
            f.addPassword("member-" + i);
        }
        int fp = 0;
        int trials = 50_000;
        for (int i = 0; i < trials; i++) {
            if (f.mightBeBreached("non-member-" + i)) {
                fp++;
            }
        }
        double rate = (double) fp / trials;
        assertTrue(rate < 0.02, "fp rate " + rate);
        assertTrue(f.hashFunctions() >= 6 && f.hashFunctions() <= 8);
    }

    @Test
    @DisplayName("SSO-T-password-022 bloom filter loads HIBP style hex lines with counts")
    void hexLines() throws Exception {
        String sha1 = Bytes.hex(com.naqqa.sso.util.Hashes.digest("SHA-1", Bytes.utf8("password123"))).toUpperCase();
        String text = sha1 + ":2413945\n\n" + "0".repeat(40) + ":1\n";
        BreachedPasswordFilter f = BreachedPasswordFilter.fromHexLines(new StringReader(text), 10, 10, 0.001);
        assertTrue(f.mightBeBreached("password123"));
        assertFalse(f.mightBeBreached("password124 is fine"));
        assertEquals("breached.bad_line", code(() -> {
            try {
                BreachedPasswordFilter.fromHexLines(new StringReader("XYZ:1\n"), 10, 10, 0.001);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    @DisplayName("SSO-T-password-023 bloom filter serialization round-trips and rejects corruption")
    void bloomSerialization() throws Exception {
        BreachedPasswordFilter f = BreachedPasswordFilter.create(10, 1000, 0.01);
        f.addPassword("hunter2hunter2");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        f.writeTo(out);
        byte[] bytes = out.toByteArray();
        BreachedPasswordFilter back = BreachedPasswordFilter.readFrom(new ByteArrayInputStream(bytes));
        assertTrue(back.mightBeBreached("hunter2hunter2"));
        assertEquals(f.bitCount(), back.bitCount());
        byte[] badMagic = bytes.clone();
        badMagic[0] = 'X';
        assertEquals("breached.format", code(() -> read(badMagic)));
        assertEquals("breached.format", code(() -> read(Arrays.copyOf(bytes, bytes.length + 1))));
        assertEquals("breached.format", code(() -> read(Arrays.copyOf(bytes, bytes.length - 3))));
    }

    private static void read(byte[] b) {
        try {
            BreachedPasswordFilter.readFrom(new ByteArrayInputStream(b));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("SSO-T-password-024 bloom filter rejects truncated records and invalid parameters")
    void bloomParams() {
        assertEquals("breached.truncated_record", code(() -> {
            try {
                BreachedPasswordFilter.fromSha1Prefixes(new ByteArrayInputStream(new byte[15]), 10, 10, 0.01);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }));
        assertEquals("breached.prefix_invalid", code(() -> BreachedPasswordFilter.create(4, 10, 0.01)));
        assertEquals("breached.params_invalid", code(() -> BreachedPasswordFilter.create(10, 10, 0.9)));
        assertEquals("breached.too_large", code(() -> BreachedPasswordFilter.create(10, 10_000_000_000L, 0.0001)));
        assertTrue(Set.of(10).contains(BreachedPasswordFilter.DEFAULT_PREFIX_BYTES));
    }
}
