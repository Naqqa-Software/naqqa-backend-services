package com.naqqa.sso.mfa;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Base32Test {
    private static String enc(String s) {
        return Base32.encode(Bytes.utf8(s), true);
    }

    @Test
    @DisplayName("SSO-T-mfa-001 base32 RFC 4648 encode vectors")
    void encodeVectors() {
        assertEquals("", enc(""));
        assertEquals("MY======", enc("f"));
        assertEquals("MZXQ====", enc("fo"));
        assertEquals("MZXW6===", enc("foo"));
        assertEquals("MZXW6YQ=", enc("foob"));
        assertEquals("MZXW6YTB", enc("fooba"));
        assertEquals("MZXW6YTBOI======", enc("foobar"));
    }

    @Test
    @DisplayName("SSO-T-mfa-002 base32 RFC 4648 decode vectors with and without padding")
    void decodeVectors() {
        assertEquals("foobar", Bytes.utf8(Base32.decode("MZXW6YTBOI======")));
        assertEquals("foobar", Bytes.utf8(Base32.decode("MZXW6YTBOI")));
        assertEquals("f", Bytes.utf8(Base32.decode("MY")));
        assertEquals("foo", Bytes.utf8(Base32.decode("MZXW6===")));
    }

    @Test
    @DisplayName("SSO-T-mfa-003 base32 rejects lowercase, whitespace and foreign characters")
    void rejectsLoose() {
        assertThrows(SsoException.class, () -> Base32.decode("mzxw6ytb"));
        assertThrows(SsoException.class, () -> Base32.decode("MZXW 6YTB"));
        assertThrows(SsoException.class, () -> Base32.decode("MZXW6YT1"));
        assertThrows(SsoException.class, () -> Base32.decode("MZXW6YTé"));
        assertThrows(SsoException.class, () -> Base32.decode(null));
    }

    @Test
    @DisplayName("SSO-T-mfa-004 base32 rejects bad padding and impossible lengths")
    void rejectsBadPadding() {
        assertThrows(SsoException.class, () -> Base32.decode("MY====="));
        assertThrows(SsoException.class, () -> Base32.decode("MY=======".substring(0, 7)));
        assertThrows(SsoException.class, () -> Base32.decode("M"));
        assertThrows(SsoException.class, () -> Base32.decode("MZX"));
        assertThrows(SsoException.class, () -> Base32.decode("MZXW6=YQ"));
    }

    @Test
    @DisplayName("SSO-T-mfa-005 base32 rejects non-zero trailing bits")
    void rejectsTrailingBits() {
        assertThrows(SsoException.class, () -> Base32.decode("MZ"));
    }

    @Test
    @DisplayName("SSO-T-mfa-006 base32 round trips random data of all lengths")
    void roundTrip() {
        for (int i = 0; i < 64; i++) {
            byte[] d = Bytes.random(i);
            assertArrayEquals(d, Base32.decode(Base32.encode(d)));
            assertArrayEquals(d, Base32.decode(Base32.encode(d, true)));
        }
    }
}
