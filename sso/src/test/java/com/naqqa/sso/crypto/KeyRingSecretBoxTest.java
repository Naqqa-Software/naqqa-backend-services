package com.naqqa.sso.crypto;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyRingSecretBoxTest {
    private static final byte[] AAD = SecretBox.aad("tenant-1", "user-9", "totp-secret");
    private static final byte[] PT = "JBSWY3DPEHPK3PXP".getBytes(StandardCharsets.UTF_8);

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    @Test
    @DisplayName("SSO-T-crypto-036 KeyRing rotate keeps retired key verify-only until notAfter")
    void rotate() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SigningKey first = SigningKey.generate(SigningAlgorithm.ES256);
        KeyRing ring = KeyRing.of(clock, first);
        assertEquals(first.kid(), ring.active().kid());
        SigningKey second = SigningKey.generate(SigningAlgorithm.ES256);
        ring.rotate(second, Duration.ofHours(1));
        assertEquals(second.kid(), ring.active().kid());
        SigningKey retired = ring.find(first.kid()).orElseThrow();
        assertFalse(retired.canSign());
        assertEquals(2, ring.verificationKeys().size());
        clock.advance(Duration.ofHours(1));
        assertTrue(ring.find(first.kid()).isEmpty());
        assertEquals(1, ring.verificationKeys().size());
        assertEquals(1, ring.prune());
        assertEquals(1, ring.entries().size());
    }

    @Test
    @DisplayName("SSO-T-crypto-037 KeyRing lookup ignores unknown and malformed kids")
    void lookup() {
        KeyRing ring = KeyRing.of(new MutableClock(Instant.EPOCH), SigningKey.generate(SigningAlgorithm.EdDSA));
        assertTrue(ring.find("nope").isEmpty());
        assertTrue(ring.find("../../etc/passwd\u0000").isEmpty());
        assertTrue(ring.find(null).isEmpty());
        assertTrue(ring.find("x".repeat(500)).isEmpty());
    }

    @Test
    @DisplayName("SSO-T-crypto-038 KeyRing active key must hold a private key; duplicates and active removal refused")
    void ringRules() {
        SigningKey k = SigningKey.generate(SigningAlgorithm.ES256);
        MutableClock clock = new MutableClock(Instant.EPOCH);
        assertEquals("keyring.active_requires_private", code(() -> KeyRing.of(clock, k.publicOnly())));
        KeyRing ring = KeyRing.of(clock, k);
        assertEquals("keyring.duplicate_kid", code(() -> ring.rotate(k, Duration.ZERO)));
        assertEquals("keyring.cannot_remove_active", code(() -> ring.remove(k.kid())));
        assertEquals("keyring.retire_invalid", code(() -> ring.rotate(SigningKey.generate(SigningAlgorithm.ES256), Duration.ofSeconds(-1))));
    }

    @Test
    @DisplayName("SSO-T-crypto-039 verify-only KeyRing has no active signer and accepts explicit notAfter")
    void verifyOnlyRing() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        SigningKey a = SigningKey.generate(SigningAlgorithm.RS256);
        KeyRing ring = KeyRing.verifyOnly(clock, List.of(a));
        assertEquals("keyring.no_active_key", code(ring::active));
        SigningKey b = SigningKey.generate(SigningAlgorithm.EdDSA);
        ring.addVerifyOnly(b, Instant.EPOCH.plusSeconds(10));
        assertTrue(ring.find(b.kid()).isPresent());
        clock.advance(Duration.ofSeconds(10));
        assertTrue(ring.find(b.kid()).isEmpty());
        assertTrue(ring.remove(a.kid()));
    }

    @Test
    @DisplayName("SSO-T-crypto-040 SecretBox seal/open round-trip with kid || nonce96 || ct||tag layout")
    void secretBoxRoundTrip() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        byte[] sealed = box.seal(PT, AAD);
        assertEquals(2, sealed[0]);
        assertEquals("k1", new String(sealed, 1, 2, StandardCharsets.US_ASCII));
        assertEquals(1 + 2 + 12 + PT.length + 16, sealed.length);
        assertArrayEquals(PT, box.open(sealed, AAD));
        assertArrayEquals(PT, box.openString(box.sealToString(PT, AAD), AAD));
        assertEquals("k1", SecretBox.kidOf(sealed));
    }

    @Test
    @DisplayName("SSO-T-crypto-041 SecretBox requires non-empty AAD")
    void aadRequired() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        assertEquals("secretbox.aad_required", code(() -> box.seal(PT, null)));
        assertEquals("secretbox.aad_required", code(() -> box.seal(PT, new byte[0])));
        byte[] sealed = box.seal(PT, AAD);
        assertEquals("secretbox.aad_required", code(() -> box.open(sealed, null)));
    }

    @Test
    @DisplayName("SSO-T-crypto-042 SecretBox rejects wrong AAD (context binding)")
    void wrongAad() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        byte[] sealed = box.seal(PT, AAD);
        assertEquals("secretbox.open_failed", code(() -> box.open(sealed, SecretBox.aad("tenant-1", "user-8", "totp-secret"))));
        assertFalse(Arrays.equals(SecretBox.aad("ab", "c"), SecretBox.aad("a", "bc")));
    }

    @Test
    @DisplayName("SSO-T-crypto-043 SecretBox detects tampering of ciphertext, tag, nonce and kid")
    void tamper() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        box.addKey("k2", SecretBox.generateKey(), 0);
        byte[] sealed = box.seal(PT, AAD);
        for (int pos : new int[]{4, 16, sealed.length - 1}) {
            byte[] t = sealed.clone();
            t[pos] ^= 1;
            assertEquals("secretbox.open_failed", code(() -> box.open(t, AAD)));
        }
        byte[] kidSwap = sealed.clone();
        kidSwap[2] = '2';
        assertEquals("secretbox.open_failed", code(() -> box.open(kidSwap, AAD)));
        assertEquals("secretbox.open_failed", code(() -> box.open(Arrays.copyOf(sealed, 20), AAD)));
        assertEquals("secretbox.open_failed", code(() -> box.open(new byte[]{0, 1}, AAD)));
    }

    @Test
    @DisplayName("SSO-T-crypto-044 SecretBox refuses to seal after 2^32 messages per key")
    void counterCap() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        box.addKey("k2", SecretBox.generateKey(), SecretBox.MAX_MESSAGES_PER_KEY - 1);
        box.activate("k2");
        box.seal(PT, AAD);
        assertEquals(SecretBox.MAX_MESSAGES_PER_KEY, box.messagesSealed("k2"));
        assertEquals("secretbox.key_exhausted", code(() -> box.seal(PT, AAD)));
        assertEquals("secretbox.key_exhausted", code(() -> box.seal(PT, AAD)));
        box.activate("k1");
        assertArrayEquals(PT, box.open(box.seal(PT, AAD), AAD));
    }

    @Test
    @DisplayName("SSO-T-crypto-045 SecretBox key rotation: old boxes open, need reseal, fail after removal")
    void rotation() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        byte[] old = box.seal(PT, AAD);
        box.addKey("k2", SecretBox.generateKey(), 0);
        box.activate("k2");
        assertTrue(box.needsReseal(old));
        assertArrayEquals(PT, box.open(old, AAD));
        byte[] fresh = box.seal(PT, AAD);
        assertFalse(box.needsReseal(fresh));
        box.removeKey("k1");
        assertEquals("secretbox.open_failed", code(() -> box.open(old, AAD)));
        assertEquals("secretbox.cannot_remove_active", code(() -> box.removeKey("k2")));
    }

    @Test
    @DisplayName("SSO-T-crypto-046 SecretBox uses a fresh nonce for every message")
    void freshNonce() {
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        byte[] a = box.seal(PT, AAD);
        byte[] b = box.seal(PT, AAD);
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 3, 15), Arrays.copyOfRange(b, 3, 15)));
        assertFalse(Arrays.equals(a, b));
    }

    @Test
    @DisplayName("SSO-T-crypto-047 SecretBox validates key length and kid charset")
    void validation() {
        assertEquals("secretbox.key_length", code(() -> new SecretBox("k1", new byte[16])));
        assertEquals("secretbox.kid_invalid", code(() -> new SecretBox("bad kid", SecretBox.generateKey())));
        assertEquals("secretbox.kid_invalid", code(() -> new SecretBox("x".repeat(33), SecretBox.generateKey())));
        SecretBox box = new SecretBox("k1", SecretBox.generateKey());
        assertEquals("secretbox.duplicate_kid", code(() -> box.addKey("k1", SecretBox.generateKey(), 0)));
        assertEquals("secretbox.unknown_kid", code(() -> box.activate("zz")));
        assertEquals("secretbox.open_failed", code(() -> box.openString("not*base64", AAD)));
        assertEquals(32, Bytes.random(32).length);
    }
}
