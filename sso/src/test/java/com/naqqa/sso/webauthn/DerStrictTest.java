package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DerStrictTest {
    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }

    @Test
    @DisplayName("SSO-T-webauthn-213 DER OID decoding is strict: minimal subidentifiers, complete last arc, size cap, multi-byte first arc")
    void strictOid() {
        assertEquals("1.3.6.1.4.1.11129.2.1.17", Der.oid(hex("2B 06 01 04 01 D6 79 02 01 11")));
        assertEquals("2.999.3", Der.oid(hex("88 37 03")));
        assertEquals("0.9.2342", Der.oid(hex("09 92 26")));
        for (String bad : List.of("", "2B 06 01 04 01 80 D6 79 02 01 11", "2B 06 01 04 01 D6 79 02 01 11 81", "80 01",
                "2B 06 01 04 01 D6", "2B 8F FF FF FF FF 7F")) {
            assertThrows(SsoException.class, () -> Der.oid(hex(bad)), bad);
        }
        assertThrows(SsoException.class, () -> Der.oid(new byte[65]));
    }

    @Test
    @DisplayName("SSO-T-webauthn-214 DER TLV and INTEGER are strict: minimal tag, minimal length, minimal two's complement, INTEGER tag")
    void strictTlv() {
        assertEquals(5, Der.integer(Der.readSingle(hex("02 01 05"))));
        assertEquals(128, Der.integer(Der.readSingle(hex("02 02 00 80"))));
        assertEquals(-128, Der.integer(Der.readSingle(hex("02 01 80"))));
        for (String bad : List.of("1F 05 00", "1F 80 1F 00", "04 81 05 0102030405", "04 82 00 80" + "00".repeat(128))) {
            assertThrows(SsoException.class, () -> Der.readSingle(hex(bad)), bad);
        }
        for (String bad : List.of("02 02 00 05", "02 02 FF 80", "04 01 05", "0A 01 02", "22 03 02 01 05", "02 00")) {
            assertThrows(SsoException.class, () -> Der.integer(Der.readSingle(hex(bad))), bad);
        }
    }
}
