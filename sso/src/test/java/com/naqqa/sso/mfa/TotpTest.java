package com.naqqa.sso.mfa;

import com.naqqa.sso.p2support.TestClock;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    private static final long[] TIMES = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};

    private void vectors(OtpAlgorithm alg, String secret, String[] expected) {
        TotpConfig cfg = new TotpConfig(alg, 8, 30, 1);
        byte[] key = Bytes.utf8(secret);
        for (int i = 0; i < TIMES.length; i++) {
            assertEquals(expected[i], Totp.generate(key, TIMES[i], cfg), alg + " t=" + TIMES[i]);
        }
    }

    @Test
    @DisplayName("SSO-T-mfa-012 TOTP RFC 6238 Appendix B SHA1 vectors")
    void sha1() {
        vectors(OtpAlgorithm.SHA1, "12345678901234567890",
                new String[]{"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"});
    }

    @Test
    @DisplayName("SSO-T-mfa-013 TOTP RFC 6238 Appendix B SHA256 vectors")
    void sha256() {
        vectors(OtpAlgorithm.SHA256, "12345678901234567890123456789012",
                new String[]{"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"});
    }

    @Test
    @DisplayName("SSO-T-mfa-014 TOTP RFC 6238 Appendix B SHA512 vectors")
    void sha512() {
        vectors(OtpAlgorithm.SHA512, "1234567890123456789012345678901234567890123456789012345678901234",
                new String[]{"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"});
    }

    private final byte[] secret = Bytes.utf8("12345678901234567890");

    @Test
    @DisplayName("SSO-T-mfa-015 TOTP accepts current code")
    void acceptsCurrent() {
        TestClock clock = TestClock.epoch(1111111109L);
        Totp totp = new Totp(clock, TotpConfig.defaults(), new InMemoryTotpStepStore());
        assertTrue(totp.verify("u", secret, totp.current(secret)).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-016 TOTP replay of the same code is rejected")
    void replay() {
        TestClock clock = TestClock.epoch(1111111109L);
        Totp totp = new Totp(clock, TotpConfig.defaults(), new InMemoryTotpStepStore());
        String code = totp.current(secret);
        assertTrue(totp.verify("u", secret, code).valid());
        assertFalse(totp.verify("u", secret, code).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-017 TOTP earlier step rejected after a later step was accepted")
    void earlierStepRejected() {
        TestClock clock = TestClock.epoch(1111111109L);
        TotpConfig cfg = TotpConfig.defaults();
        Totp totp = new Totp(clock, cfg, new InMemoryTotpStepStore());
        String prev = Totp.generate(secret, clock.instant().getEpochSecond() - 30, cfg);
        String cur = Totp.generate(secret, clock.instant().getEpochSecond(), cfg);
        assertTrue(totp.verify("u", secret, cur).valid());
        assertFalse(totp.verify("u", secret, prev).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-018 TOTP window plus and minus one accepted, two rejected")
    void window() {
        TestClock clock = TestClock.epoch(1111111109L);
        TotpConfig cfg = TotpConfig.defaults();
        long now = clock.instant().getEpochSecond();
        assertTrue(new Totp(clock, cfg, new InMemoryTotpStepStore()).verify("u", secret, Totp.generate(secret, now - 30, cfg)).valid());
        assertTrue(new Totp(clock, cfg, new InMemoryTotpStepStore()).verify("u", secret, Totp.generate(secret, now + 30, cfg)).valid());
        assertFalse(new Totp(clock, cfg, new InMemoryTotpStepStore()).verify("u", secret, Totp.generate(secret, now - 60, cfg)).valid());
        assertFalse(new Totp(clock, cfg, new InMemoryTotpStepStore()).verify("u", secret, Totp.generate(secret, now + 60, cfg)).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-019 TOTP window zero accepts only the current step")
    void windowZero() {
        TestClock clock = TestClock.epoch(1111111109L);
        TotpConfig cfg = new TotpConfig(OtpAlgorithm.SHA1, 6, 30, 0);
        long now = clock.instant().getEpochSecond();
        assertFalse(new Totp(clock, cfg, new InMemoryTotpStepStore()).verify("u", secret, Totp.generate(secret, now - 30, cfg)).valid());
        assertTrue(new Totp(clock, cfg, new InMemoryTotpStepStore()).verify("u", secret, Totp.generate(secret, now, cfg)).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-020 TOTP rejects malformed codes and wrong length without state change")
    void malformed() {
        TestClock clock = TestClock.epoch(1111111109L);
        InMemoryTotpStepStore store = new InMemoryTotpStepStore();
        Totp totp = new Totp(clock, TotpConfig.defaults(), store);
        assertFalse(totp.verify("u", secret, null).valid());
        assertFalse(totp.verify("u", secret, "12345").valid());
        assertFalse(totp.verify("u", secret, "1234567").valid());
        assertFalse(totp.verify("u", secret, "12a456").valid());
        assertEquals(-1, store.lastStep("u"));
    }

    @Test
    @DisplayName("SSO-T-mfa-021 TOTP replay state is per user")
    void perUser() {
        TestClock clock = TestClock.epoch(1111111109L);
        Totp totp = new Totp(clock, TotpConfig.defaults(), new InMemoryTotpStepStore());
        String code = totp.current(secret);
        assertTrue(totp.verify("alice", secret, code).valid());
        assertTrue(totp.verify("bob", secret, code).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-022 TOTP next step code accepted after advancing time")
    void nextStep() {
        TestClock clock = TestClock.epoch(1111111109L);
        Totp totp = new Totp(clock, TotpConfig.defaults(), new InMemoryTotpStepStore());
        assertTrue(totp.verify("u", secret, totp.current(secret)).valid());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(totp.verify("u", secret, totp.current(secret)).valid());
    }

    @Test
    @DisplayName("SSO-T-mfa-023 TOTP brute force of 1000 wrong codes never succeeds")
    void bruteForce() {
        TestClock clock = TestClock.epoch(1111111109L);
        Totp totp = new Totp(clock, TotpConfig.defaults(), new InMemoryTotpStepStore());
        String real0 = Totp.generate(secret, 1111111109L - 30, TotpConfig.defaults());
        String real1 = totp.current(secret);
        String real2 = Totp.generate(secret, 1111111109L + 30, TotpConfig.defaults());
        int hits = 0;
        for (int i = 0; i < 1000; i++) {
            String guess = String.format("%06d", i);
            if (guess.equals(real0) || guess.equals(real1) || guess.equals(real2)) {
                continue;
            }
            if (totp.verify("u", secret, guess).valid()) {
                hits++;
            }
        }
        assertEquals(0, hits);
    }

    @Test
    @DisplayName("SSO-T-mfa-024 TOTP config validation")
    void configValidation() {
        assertThrows(com.naqqa.sso.SsoException.class, () -> new TotpConfig(OtpAlgorithm.SHA1, 5, 30, 1));
        assertThrows(com.naqqa.sso.SsoException.class, () -> new TotpConfig(OtpAlgorithm.SHA1, 6, 0, 1));
        assertThrows(com.naqqa.sso.SsoException.class, () -> new TotpConfig(OtpAlgorithm.SHA1, 6, 30, -1));
        assertThrows(com.naqqa.sso.SsoException.class, () -> new TotpConfig(null, 6, 30, 1));
    }
}
