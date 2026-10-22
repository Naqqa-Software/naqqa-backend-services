package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OtpAuthUriTest {
    private static final byte[] SECRET = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    @DisplayName("SSO-T-mfa-025 otpauth URI has issuer, label, base32 secret and parameters")
    void basic() {
        String uri = OtpAuthUri.totp("Naqqa", "alice@example.com", SECRET, TotpConfig.defaults());
        assertEquals("otpauth://totp/Naqqa:alice%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Naqqa&algorithm=SHA1&digits=6&period=30", uri);
    }

    @Test
    @DisplayName("SSO-T-mfa-026 otpauth URI percent-encodes spaces and reserved characters")
    void encoding() {
        String uri = OtpAuthUri.totp("My App & Co", "a b/c?d", SECRET, TotpConfig.defaults());
        assertTrue(uri.startsWith("otpauth://totp/My%20App%20%26%20Co:a%20b%2Fc%3Fd?"));
        assertTrue(uri.contains("issuer=My%20App%20%26%20Co"));
        assertFalse(uri.contains("+"));
    }

    @Test
    @DisplayName("SSO-T-mfa-027 otpauth URI reflects algorithm digits and period")
    void params() {
        String uri = OtpAuthUri.totp("N", "a", SECRET, new TotpConfig(OtpAlgorithm.SHA512, 8, 60, 1));
        assertTrue(uri.contains("algorithm=SHA512"));
        assertTrue(uri.contains("digits=8"));
        assertTrue(uri.contains("period=60"));
    }

    @Test
    @DisplayName("SSO-T-mfa-028 otpauth HOTP URI carries counter")
    void hotp() {
        String uri = OtpAuthUri.hotp("N", "a", SECRET, OtpAlgorithm.SHA1, 6, 7);
        assertTrue(uri.startsWith("otpauth://hotp/N:a?"));
        assertTrue(uri.endsWith("counter=7"));
    }

    @Test
    @DisplayName("SSO-T-mfa-029 otpauth URI rejects colon in issuer and empty values")
    void invalid() {
        assertThrows(SsoException.class, () -> OtpAuthUri.totp("a:b", "x", SECRET, TotpConfig.defaults()));
        assertThrows(SsoException.class, () -> OtpAuthUri.totp("", "x", SECRET, TotpConfig.defaults()));
        assertThrows(SsoException.class, () -> OtpAuthUri.totp("a", "", SECRET, TotpConfig.defaults()));
        assertThrows(SsoException.class, () -> OtpAuthUri.totp(null, "x", SECRET, TotpConfig.defaults()));
    }

    @Test
    @DisplayName("SSO-T-mfa-030 otpauth URI cannot be injected through the label")
    void injection() {
        String uri = OtpAuthUri.totp("N", "a&secret=EVIL", SECRET, TotpConfig.defaults());
        assertTrue(uri.contains("a%26secret%3DEVIL"));
        assertFalse(uri.contains("&secret=EVIL"));
    }
}
