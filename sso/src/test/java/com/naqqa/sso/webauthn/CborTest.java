package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CborTest {
    private static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    private static String code(String h) {
        return assertThrows(SsoException.class, () -> Cbor.decode(hex(h))).code();
    }

    @Test
    @DisplayName("SSO-T-webauthn-001 RFC 8949 integer vectors decode")
    void integers() {
        assertEquals(0L, Cbor.decode(hex("00")));
        assertEquals(23L, Cbor.decode(hex("17")));
        assertEquals(24L, Cbor.decode(hex("1818")));
        assertEquals(1000L, Cbor.decode(hex("1903e8")));
        assertEquals(1000000L, Cbor.decode(hex("1a000f4240")));
        assertEquals(1000000000000L, Cbor.decode(hex("1b000000e8d4a51000")));
        assertEquals(-1L, Cbor.decode(hex("20")));
        assertEquals(-1000L, Cbor.decode(hex("3903e7")));
    }

    @Test
    @DisplayName("SSO-T-webauthn-002 strings, arrays, maps and simple values decode")
    void structures() {
        assertArrayEquals(hex("01020304"), (byte[]) Cbor.decode(hex("4401020304")));
        assertEquals("IETF", Cbor.decode(hex("6449455446")));
        assertEquals(List.of(1L, 2L, 3L), Cbor.decode(hex("83010203")));
        Map<?, ?> m = (Map<?, ?>) Cbor.decode(hex("a201020304"));
        assertEquals(2L, m.get(1L));
        assertEquals(4L, m.get(3L));
        assertEquals(Boolean.FALSE, Cbor.decode(hex("f4")));
        assertEquals(Boolean.TRUE, Cbor.decode(hex("f5")));
        assertEquals(Cbor.Simple.NULL, Cbor.decode(hex("f6")));
    }

    @Test
    @DisplayName("SSO-T-webauthn-003 encoder round trips nested structures")
    void roundTrip() {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("fmt", "none");
        m.put(-7L, List.of(1L, -300L, new byte[]{1, 2}, Boolean.TRUE, Cbor.Simple.NULL));
        m.put(1L, 4294967296L);
        byte[] enc = Cbor.encode(m);
        Map<?, ?> back = (Map<?, ?>) Cbor.decode(enc);
        assertEquals("none", back.get("fmt"));
        assertEquals(4294967296L, back.get(1L));
        assertArrayEquals(enc, Cbor.encode(back));
    }

    @Test
    @DisplayName("SSO-T-webauthn-004 duplicate map keys rejected")
    void duplicateKeys() {
        assertEquals("webauthn.cbor.duplicate_key", code("a201020103"));
        assertEquals("webauthn.cbor.duplicate_key", code("a2616101616102"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-005 indefinite length items rejected")
    void indefinite() {
        assertEquals("webauthn.cbor.indefinite", code("5f42010243030405ff"));
        assertEquals("webauthn.cbor.indefinite", code("9f0102ff"));
        assertEquals("webauthn.cbor.indefinite", code("bf6161f5ff"));
        assertEquals("webauthn.cbor.indefinite", code("7f6161ff"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-006 trailing bytes rejected")
    void trailing() {
        assertEquals("webauthn.cbor.trailing", code("0000"));
        assertEquals("webauthn.cbor.trailing", code("a0ff"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-007 nesting deeper than 16 rejected, 16 accepted")
    void depth() {
        assertEquals("webauthn.cbor.depth", code("81".repeat(17) + "00"));
        Object v = Cbor.decode(hex("81".repeat(16) + "00"));
        assertEquals(List.class.isInstance(v), true);
    }

    @Test
    @DisplayName("SSO-T-webauthn-008 tags rejected")
    void tags() {
        assertEquals("webauthn.cbor.tag", code("c11a514b67b0"));
        assertEquals("webauthn.cbor.tag", code("d8184100"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-009 floats rejected")
    void floats() {
        assertEquals("webauthn.cbor.float", code("f93c00"));
        assertEquals("webauthn.cbor.float", code("fa47c35000"));
        assertEquals("webauthn.cbor.float", code("fb3ff199999999999a"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-010 undefined and other simple values rejected")
    void simple() {
        assertEquals("webauthn.cbor.simple", code("f7"));
        assertEquals("webauthn.cbor.simple", code("f0"));
        assertEquals("webauthn.cbor.simple", code("f818"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-011 non-shortest integer and length encodings rejected")
    void nonCanonical() {
        assertEquals("webauthn.cbor.non_canonical", code("1817"));
        assertEquals("webauthn.cbor.non_canonical", code("190017"));
        assertEquals("webauthn.cbor.non_canonical", code("1a0000ffff"));
        assertEquals("webauthn.cbor.non_canonical", code("1b00000000ffffffff"));
        assertEquals("webauthn.cbor.non_canonical", code("580101"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-012 reserved additional info rejected")
    void reserved() {
        assertEquals("webauthn.cbor.malformed", code("1c"));
        assertEquals("webauthn.cbor.malformed", code("1e"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-013 integers beyond signed 64-bit rejected")
    void intRange() {
        assertEquals("webauthn.cbor.int_range", code("1bffffffffffffffff"));
        assertEquals("webauthn.cbor.int_range", code("3b8000000000000000"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-014 truncated input and lying lengths rejected without allocation")
    void truncated() {
        assertEquals("webauthn.cbor.truncated", code("44010203"));
        assertEquals("webauthn.cbor.truncated", code("5affffffff00"));
        assertEquals("webauthn.cbor.truncated", code("83 01 02".replace(" ", "")));
        assertEquals("webauthn.cbor.truncated", code("19"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-015 container entry count and item count limits enforced")
    void limits() {
        assertEquals("webauthn.cbor.limit", code("9a00010000" + "00".repeat(16)));
        StringBuilder many = new StringBuilder("99" + String.format("%04x", 1000));
        for (int i = 0; i < 1000; i++) {
            many.append("8400000000");
        }
        assertEquals("webauthn.cbor.limit", code(many.toString()));
        assertEquals("webauthn.cbor.limit", assertThrows(SsoException.class,
                () -> Cbor.decode(new byte[Cbor.MAX_INPUT_BYTES + 1])).code());
    }

    @Test
    @DisplayName("SSO-T-webauthn-016 invalid UTF-8 in text strings rejected")
    void utf8() {
        assertEquals("webauthn.cbor.utf8", code("62c328"));
        assertEquals("webauthn.cbor.utf8", code("63eda080"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-017 map keys other than integers and text rejected")
    void mapKeys() {
        assertEquals("webauthn.cbor.map_key", code("a1410101"));
        assertEquals("webauthn.cbor.map_key", code("a1f501"));
        assertEquals("webauthn.cbor.map_key", code("a1800101"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-018 decodeFirst reports the end offset for embedded items")
    void decodeFirst() {
        Cbor.Decoded d = Cbor.decodeFirst(hex("ff a10102 07".replace(" ", "")), 1);
        assertEquals(4, d.end());
        assertEquals(Map.of(1L, 2L), d.value());
    }

    @Test
    @DisplayName("SSO-T-webauthn-019 break byte and empty input rejected")
    void breakAndEmpty() {
        assertEquals("webauthn.cbor.indefinite", code("ff"));
        assertEquals("webauthn.cbor.truncated", assertThrows(SsoException.class, () -> Cbor.decode(new byte[0])).code());
    }
}
