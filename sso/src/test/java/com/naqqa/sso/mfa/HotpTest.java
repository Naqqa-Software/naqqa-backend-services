package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HotpTest {
    private static final byte[] SECRET = Bytes.utf8("12345678901234567890");

    @Test
    @DisplayName("SSO-T-mfa-007 HOTP RFC 4226 Appendix D vectors counters 0-9")
    void rfcVectors() {
        String[] expected = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], Hotp.generate(SECRET, i));
        }
    }

    @Test
    @DisplayName("SSO-T-mfa-008 HOTP supports 7 and 8 digits (truncated value RFC 4226)")
    void digits() {
        assertEquals("4755224", Hotp.generate(SECRET, 0, 7, OtpAlgorithm.SHA1));
        assertEquals("84755224", Hotp.generate(SECRET, 0, 8, OtpAlgorithm.SHA1));
    }

    @Test
    @DisplayName("SSO-T-mfa-009 HOTP pads leading zeros")
    void leadingZeros() {
        for (long c = 0; c < 300; c++) {
            assertEquals(6, Hotp.generate(SECRET, c).length());
        }
    }

    @Test
    @DisplayName("SSO-T-mfa-010 HOTP rejects invalid digits, counter and short secret")
    void invalidInputs() {
        assertThrows(SsoException.class, () -> Hotp.generate(SECRET, 0, 5, OtpAlgorithm.SHA1));
        assertThrows(SsoException.class, () -> Hotp.generate(SECRET, 0, 9, OtpAlgorithm.SHA1));
        assertThrows(SsoException.class, () -> Hotp.generate(SECRET, -1, 6, OtpAlgorithm.SHA1));
        assertThrows(SsoException.class, () -> Hotp.generate(new byte[4], 0));
        assertThrows(SsoException.class, () -> Hotp.generate(null, 0));
    }

    @Test
    @DisplayName("SSO-T-mfa-011 HOTP different algorithms give different codes")
    void algorithms() {
        String a = Hotp.generate(SECRET, 1, 8, OtpAlgorithm.SHA1);
        String b = Hotp.generate(SECRET, 1, 8, OtpAlgorithm.SHA256);
        String c = Hotp.generate(SECRET, 1, 8, OtpAlgorithm.SHA512);
        assertNotEquals(a, b);
        assertNotEquals(b, c);
    }
}
