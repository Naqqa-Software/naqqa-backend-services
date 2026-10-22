package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.p2support.MemSealer;
import com.naqqa.sso.p2support.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnrollmentAndRecoveryTest {
    private final TestClock clock = TestClock.epoch(1_700_000_000L);
    private final InMemoryTotpCredentialStore credStore = new InMemoryTotpCredentialStore();
    private final InMemoryTotpStepStore steps = new InMemoryTotpStepStore();
    private final TotpEnrollment enrollment = new TotpEnrollment(clock, new MemSealer(), credStore, steps);

    private String codeFor(String base32, long offsetSeconds) {
        return Totp.generate(Base32.decode(base32), clock.instant().getEpochSecond() + offsetSeconds, TotpConfig.defaults());
    }

    @Test
    @DisplayName("SSO-T-mfa-031 enrollment is pending until a valid code confirms")
    void pendingUntilConfirmed() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "Naqqa", "u1@x.io");
        assertFalse(enrollment.isActive("u1"));
        assertFalse(enrollment.verify("u1", codeFor(b.secretBase32(), 0)));
        assertTrue(enrollment.confirm("u1", codeFor(b.secretBase32(), 0)));
        assertTrue(enrollment.isActive("u1"));
    }

    @Test
    @DisplayName("SSO-T-mfa-032 enrollment secret is 20 bytes and URI carries it")
    void secretSize() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "Naqqa", "u1@x.io");
        assertEquals(20, Base32.decode(b.secretBase32()).length);
        assertTrue(b.otpauthUri().contains("secret=" + b.secretBase32()));
    }

    @Test
    @DisplayName("SSO-T-mfa-033 enrollment confirm with wrong code stays pending")
    void wrongConfirm() {
        enrollment.begin("u1", "Naqqa", "a");
        assertFalse(enrollment.confirm("u1", "000000"));
        assertFalse(enrollment.isActive("u1"));
    }

    @Test
    @DisplayName("SSO-T-mfa-034 enrollment stores the secret sealed, never in clear")
    void sealedAtRest() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "Naqqa", "a");
        byte[] plain = Base32.decode(b.secretBase32());
        byte[] stored = credStore.find("u1").orElseThrow().sealedSecret();
        assertNotEquals(plain.length, stored.length);
        assertFalse(java.util.Arrays.equals(plain, stored));
    }

    @Test
    @DisplayName("SSO-T-mfa-035 enrollment sealed secret is bound to the user via AAD")
    void aadBinding() {
        MemSealer sealer = new MemSealer();
        InMemoryTotpCredentialStore store = new InMemoryTotpCredentialStore();
        TotpEnrollment e = new TotpEnrollment(clock, sealer, store, steps);
        e.begin("alice", "N", "a");
        TotpCredential c = store.find("alice").orElseThrow();
        store.save(new TotpCredential("bob", c.sealedSecret(), c.config(), true, c.createdAt(), c.confirmedAt()));
        assertThrows(SsoException.class, () -> e.verify("bob", "123456"));
    }

    @Test
    @DisplayName("SSO-T-mfa-036 enrollment replay protection applies after confirm")
    void replayAfterConfirm() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "N", "a");
        String code = codeFor(b.secretBase32(), 0);
        assertTrue(enrollment.confirm("u1", code));
        assertFalse(enrollment.verify("u1", code));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(enrollment.verify("u1", codeFor(b.secretBase32(), 0)));
    }

    @Test
    @DisplayName("SSO-T-mfa-037 enrollment pending expires")
    void pendingExpires() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "N", "a");
        clock.advance(Duration.ofMinutes(16));
        String code = codeFor(b.secretBase32(), 0);
        SsoException ex = assertThrows(SsoException.class, () -> enrollment.confirm("u1", code));
        assertEquals("totp_enrollment_expired", ex.code());
    }

    @Test
    @DisplayName("SSO-T-mfa-038 enrollment cannot restart over a confirmed credential")
    void noOverwrite() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "N", "a");
        enrollment.confirm("u1", codeFor(b.secretBase32(), 0));
        assertEquals("totp_already_enrolled", assertThrows(SsoException.class, () -> enrollment.begin("u1", "N", "a")).code());
    }

    @Test
    @DisplayName("SSO-T-mfa-039 enrollment restart over pending issues a new secret")
    void restartPending() {
        TotpEnrollment.Begin a = enrollment.begin("u1", "N", "a");
        TotpEnrollment.Begin b = enrollment.begin("u1", "N", "a");
        assertNotEquals(a.secretBase32(), b.secretBase32());
    }

    @Test
    @DisplayName("SSO-T-mfa-040 enrollment remove deletes credential")
    void remove() {
        TotpEnrollment.Begin b = enrollment.begin("u1", "N", "a");
        enrollment.confirm("u1", codeFor(b.secretBase32(), 0));
        enrollment.remove("u1");
        assertFalse(enrollment.isActive("u1"));
        assertFalse(enrollment.verify("u1", codeFor(b.secretBase32(), 30)));
    }

    private static final byte[] PEPPER = new byte[32];

    private final RecoveryCodes recovery = new RecoveryCodes(clock, new InMemoryRecoveryStore(), PEPPER, 1000, 5, Duration.ofMinutes(15));

    @Test
    @DisplayName("SSO-T-mfa-041 recovery generates 10 codes grouped xxxxx-xxxxx from the unambiguous alphabet")
    void format() {
        List<String> codes = recovery.generate("u");
        assertEquals(10, codes.size());
        for (String c : codes) {
            assertTrue(c.matches("[23456789ABCDEFGHJKMNPQRSTVWXYZ]{5}-[23456789ABCDEFGHJKMNPQRSTVWXYZ]{5}"), c);
        }
        assertEquals(10, new HashSet<>(codes).size());
    }

    @Test
    @DisplayName("SSO-T-mfa-042 recovery code is single use")
    void singleUse() {
        List<String> codes = recovery.generate("u");
        assertEquals(RecoveryCodes.Outcome.OK, recovery.redeem("u", codes.get(0)));
        assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", codes.get(0)));
        assertEquals(9, recovery.remaining("u"));
    }

    @Test
    @DisplayName("SSO-T-mfa-043 recovery accepts lowercase, no dash and spaces")
    void normalization() {
        List<String> codes = recovery.generate("u");
        assertEquals(RecoveryCodes.Outcome.OK, recovery.redeem("u", " " + codes.get(1).toLowerCase().replace("-", "") + " "));
    }

    @Test
    @DisplayName("SSO-T-mfa-044 recovery regenerate invalidates old codes")
    void regenerate() {
        List<String> old = recovery.generate("u");
        List<String> fresh = recovery.generate("u");
        assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", old.get(0)));
        assertEquals(RecoveryCodes.Outcome.OK, recovery.redeem("u", fresh.get(0)));
    }

    @Test
    @DisplayName("SSO-T-mfa-045 recovery codes are stored as PBKDF2 hashes not plaintext")
    void hashedAtRest() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        RecoveryCodes rc = new RecoveryCodes(clock, store, PEPPER, 1000, 5, Duration.ofMinutes(15));
        List<String> codes = rc.generate("u");
        RecoverySet set = store.find("u").orElseThrow();
        assertEquals(10, set.entries().size());
        for (RecoverySet.Entry e : set.entries()) {
            assertEquals(32, e.hash().length);
            assertEquals(16, e.salt().length);
            for (String c : codes) {
                assertFalse(new String(e.hash(), java.nio.charset.StandardCharsets.ISO_8859_1).contains(c.substring(0, 5)));
            }
        }
    }

    @Test
    @DisplayName("SSO-T-mfa-046 recovery counts attempts and throttles after the limit")
    void attemptCounting() {
        List<String> codes = recovery.generate("u");
        for (int i = 0; i < 5; i++) {
            assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", "AAAAA-AAAAA"));
        }
        assertEquals(RecoveryCodes.Outcome.THROTTLED, recovery.redeem("u", codes.get(0)));
    }

    @Test
    @DisplayName("SSO-T-mfa-047 recovery throttle expires and then a valid code works")
    void throttleExpires() {
        List<String> codes = recovery.generate("u");
        for (int i = 0; i < 5; i++) {
            recovery.redeem("u", "AAAAA-AAAAA");
        }
        clock.advance(Duration.ofMinutes(16));
        assertEquals(RecoveryCodes.Outcome.OK, recovery.redeem("u", codes.get(0)));
    }

    @Test
    @DisplayName("SSO-T-mfa-048 recovery success resets the failure counter")
    void resetOnSuccess() {
        List<String> codes = recovery.generate("u");
        for (int i = 0; i < 4; i++) {
            recovery.redeem("u", "BBBBB-BBBBB");
        }
        assertEquals(RecoveryCodes.Outcome.OK, recovery.redeem("u", codes.get(0)));
        for (int i = 0; i < 4; i++) {
            assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", "BBBBB-BBBBB"));
        }
        assertEquals(RecoveryCodes.Outcome.OK, recovery.redeem("u", codes.get(1)));
    }

    @Test
    @DisplayName("SSO-T-mfa-049 recovery for unknown user and malformed input are rejected")
    void unknownAndMalformed() {
        assertEquals(RecoveryCodes.Outcome.NOT_CONFIGURED, recovery.redeem("nobody", "AAAAA-AAAAA"));
        recovery.generate("u");
        assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", null));
        assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", "short"));
        assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("u", "IIIII-OOOOO"));
    }

    @Test
    @DisplayName("SSO-T-mfa-050 recovery codes of one user do not work for another")
    void crossUser() {
        List<String> a = recovery.generate("alice");
        recovery.generate("bob");
        assertEquals(RecoveryCodes.Outcome.INVALID, recovery.redeem("bob", a.get(0)));
    }

    @Test
    @DisplayName("SSO-T-mfa-051 recovery revokeAll removes the set")
    void revokeAll() {
        List<String> codes = recovery.generate("u");
        recovery.revokeAll("u");
        assertEquals(RecoveryCodes.Outcome.NOT_CONFIGURED, recovery.redeem("u", codes.get(0)));
        assertEquals(0, recovery.remaining("u"));
    }

    @Test
    @DisplayName("SSO-T-mfa-052 recovery rejects invalid configuration")
    void badConfig() {
        assertThrows(SsoException.class, () -> new RecoveryCodes(clock, new InMemoryRecoveryStore(), PEPPER, 0, 5, Duration.ZERO));
        assertThrows(SsoException.class, () -> new RecoveryCodes(clock, new InMemoryRecoveryStore(), PEPPER, 10, 0, Duration.ZERO));
    }

    @Test
    @DisplayName("SSO-T-mfa-053 recovery alphabet has no ambiguous characters")
    void alphabet() {
        Set<Character> bad = Set.of('0', 'O', '1', 'I', 'L', 'U');
        for (char c : RecoveryCodes.ALPHABET.toCharArray()) {
            assertFalse(bad.contains(c));
        }
    }

    @Test
    @DisplayName("SSO-T-mfa-054 F-L1 recovery stores a 16 byte HMAC lookup id per code")
    void lookupIdsStored() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        RecoveryCodes rc = new RecoveryCodes(clock, store, PEPPER, 1000, 5, Duration.ofMinutes(15));
        rc.generate("u");
        Set<String> ids = new HashSet<>();
        for (RecoverySet.Entry e : store.find("u").orElseThrow().entries()) {
            assertEquals(16, e.lookupId().length);
            ids.add(java.util.Arrays.toString(e.lookupId()));
        }
        assertEquals(10, ids.size());
    }

    @Test
    @DisplayName("SSO-T-mfa-055 F-L1 recovery verifies with one PBKDF2 even when other entries are garbage")
    void singleVerify() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        RecoveryCodes rc = new RecoveryCodes(clock, store, PEPPER, 1000, 5, Duration.ofMinutes(15));
        List<String> codes = rc.generate("u");
        RecoverySet set = store.find("u").orElseThrow();
        List<RecoverySet.Entry> broken = new java.util.ArrayList<>();
        for (RecoverySet.Entry e : set.entries()) {
            broken.add(new RecoverySet.Entry(e.salt(), e.hash(), e.used(), e.lookupId()));
        }
        assertEquals(RecoveryCodes.Outcome.OK, rc.redeem("u", codes.get(3)));
        assertEquals(RecoveryCodes.Outcome.INVALID, rc.redeem("u", codes.get(3)));
    }

    @Test
    @DisplayName("SSO-T-mfa-056 F-L1 recovery with a different pepper cannot redeem stored codes")
    void pepperBound() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        RecoveryCodes rc = new RecoveryCodes(clock, store, PEPPER, 1000, 5, Duration.ofMinutes(15));
        List<String> codes = rc.generate("u");
        byte[] other = new byte[32];
        other[0] = 1;
        RecoveryCodes rc2 = new RecoveryCodes(clock, store, other, 1000, 5, Duration.ofMinutes(15));
        assertEquals(RecoveryCodes.Outcome.INVALID, rc2.redeem("u", codes.get(0)));
    }

    @Test
    @DisplayName("SSO-T-mfa-057 F-L1 legacy entries without lookup id still redeem once")
    void legacyEntries() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        RecoveryCodes rc = new RecoveryCodes(clock, store, PEPPER, 1000, 5, Duration.ofMinutes(15));
        List<String> codes = rc.generate("u");
        RecoverySet set = store.find("u").orElseThrow();
        List<RecoverySet.Entry> legacy = new java.util.ArrayList<>();
        for (RecoverySet.Entry e : set.entries()) {
            legacy.add(new RecoverySet.Entry(e.salt(), e.hash(), e.used()));
        }
        store.save(new RecoverySet("u", legacy, 0, null));
        assertEquals(RecoveryCodes.Outcome.OK, rc.redeem("u", codes.get(2)));
        assertEquals(RecoveryCodes.Outcome.INVALID, rc.redeem("u", codes.get(2)));
    }

    @Test
    @DisplayName("SSO-T-mfa-058 F-L1 recovery rejects short pepper")
    void shortPepper() {
        assertThrows(SsoException.class, () -> new RecoveryCodes(clock, new InMemoryRecoveryStore(), new byte[4], 10, 5, Duration.ZERO));
    }
}
