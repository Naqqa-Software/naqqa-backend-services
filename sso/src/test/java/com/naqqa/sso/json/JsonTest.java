package com.naqqa.sso.json;

import com.naqqa.sso.SsoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    private static void rejects(String json, String code) {
        SsoException e = assertThrows(SsoException.class, () -> Json.parse(json));
        assertEquals(code, e.code());
    }

    @Test
    @DisplayName("SSO-T-json-001 parses all value types into Map/List/String/Long/BigDecimal/Boolean/null")
    void parsesTypes() {
        Map<String, Object> m = Json.parseObject("{\"s\":\"x\",\"i\":42,\"f\":1.5,\"t\":true,\"n\":null,\"a\":[1,\"b\"],\"o\":{}}");
        assertEquals("x", m.get("s"));
        assertEquals(42L, m.get("i"));
        assertEquals(new BigDecimal("1.5"), m.get("f"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertTrue(m.containsKey("n"));
        assertNull(m.get("n"));
        assertEquals(List.of(1L, "b"), m.get("a"));
        assertTrue(m instanceof LinkedHashMap);
    }

    @Test
    @DisplayName("SSO-T-json-002 duplicate keys are rejected")
    void duplicateKeys() {
        rejects("{\"a\":1,\"a\":2}", "json.duplicate_key");
    }

    @Test
    @DisplayName("SSO-T-json-003 duplicate keys via escapes are rejected")
    void duplicateKeysEscaped() {
        rejects("{\"alg\":\"RS256\",\"\\u0061lg\":\"none\"}", "json.duplicate_key");
    }

    @Test
    @DisplayName("SSO-T-json-004 trailing data is rejected")
    void trailingData() {
        rejects("{} {}", "json.trailing_data");
        rejects("1 2", "json.trailing_data");
    }

    @Test
    @DisplayName("SSO-T-json-005 leading zeros are rejected")
    void leadingZeros() {
        rejects("01", "json.leading_zero");
        rejects("-012", "json.leading_zero");
        assertEquals(0L, Json.parse("0"));
        assertEquals(new BigDecimal("0.5"), Json.parse("0.5"));
    }

    @Test
    @DisplayName("SSO-T-json-006 NaN, Infinity and bare signs are rejected")
    void nanInfinity() {
        rejects("NaN", "json.unexpected_char");
        rejects("Infinity", "json.unexpected_char");
        rejects("-Infinity", "json.bad_number");
        rejects("+1", "json.unexpected_char");
    }

    @Test
    @DisplayName("SSO-T-json-007 malformed numbers are rejected")
    void badNumbers() {
        rejects("1.", "json.bad_number");
        rejects(".5", "json.unexpected_char");
        rejects("1e", "json.bad_number");
        rejects("1e+", "json.bad_number");
        rejects("-", "json.bad_number");
    }

    @Test
    @DisplayName("SSO-T-json-008 large integers become BigDecimal, small become Long")
    void bigIntegers() {
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertEquals(new BigDecimal("9223372036854775808"), Json.parse("9223372036854775808"));
        assertEquals(new BigDecimal("1E+3"), Json.parse("1e3"));
    }

    @Test
    @DisplayName("SSO-T-json-009 absurd exponents are rejected")
    void hugeExponent() {
        rejects("1e999999999", "json.bad_number");
    }

    @Test
    @DisplayName("SSO-T-json-010 lone high surrogate escape is rejected")
    void loneHighSurrogate() {
        rejects("\"\\ud800\"", "json.lone_surrogate");
        rejects("\"\\ud800x\"", "json.lone_surrogate");
    }

    @Test
    @DisplayName("SSO-T-json-011 lone low surrogate escape is rejected and valid pairs pass")
    void loneLowSurrogate() {
        rejects("\"\\udc00\"", "json.lone_surrogate");
        assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
    }

    @Test
    @DisplayName("SSO-T-json-012 raw lone surrogate characters are rejected")
    void rawLoneSurrogate() {
        rejects("\"a\uD800b\"", "json.lone_surrogate");
    }

    @Test
    @DisplayName("SSO-T-json-013 depth limit 64 is enforced")
    void depthLimit() {
        String ok = "[".repeat(64) + "]".repeat(64);
        Json.parse(ok);
        rejects("[".repeat(65) + "]".repeat(65), "json.too_deep");
        rejects("{\"a\":".repeat(65) + "1" + "}".repeat(65), "json.too_deep");
    }

    @Test
    @DisplayName("SSO-T-json-014 size limit is configurable and enforced")
    void sizeLimit() {
        SsoException e = assertThrows(SsoException.class, () -> Json.parse("\"abcdefghij\"", JsonLimits.ofSize(8)));
        assertEquals("json.too_large", e.code());
        assertEquals("abcdef", Json.parse("\"abcdef\"", JsonLimits.ofSize(8)));
        assertEquals(1024 * 1024, JsonLimits.DEFAULT.maxSize());
    }

    @Test
    @DisplayName("SSO-T-json-015 default 1 MiB limit rejects larger documents")
    void defaultSize() {
        String big = "\"" + "a".repeat(1024 * 1024) + "\"";
        SsoException e = assertThrows(SsoException.class, () -> Json.parse(big));
        assertEquals("json.too_large", e.code());
    }

    @Test
    @DisplayName("SSO-T-json-016 unescaped control characters in strings are rejected")
    void controlChars() {
        rejects("\"a\nb\"", "json.control_char");
        rejects("\"a\u0000b\"", "json.control_char");
    }

    @Test
    @DisplayName("SSO-T-json-017 invalid escapes are rejected")
    void badEscapes() {
        rejects("\"\\x41\"", "json.bad_escape");
        rejects("\"\\u12\"", "json.bad_escape");
        rejects("\"\\u12g4\"", "json.bad_escape");
        rejects("\"\\'\"", "json.bad_escape");
    }

    @Test
    @DisplayName("SSO-T-json-018 trailing commas, comments, single quotes rejected")
    void laxSyntax() {
        rejects("[1,]", "json.unexpected_char");
        rejects("{\"a\":1,}", "json.expected_key");
        rejects("{'a':1}", "json.expected_key");
        rejects("// c\n{}", "json.unexpected_char");
        rejects("{\"a\" 1}", "json.expected_colon");
        rejects("[1 2]", "json.expected_comma");
    }

    @Test
    @DisplayName("SSO-T-json-019 unterminated documents rejected")
    void unterminated() {
        rejects("{\"a\":1", "json.unexpected_end");
        rejects("[", "json.unexpected_end");
        rejects("\"abc", "json.unterminated_string");
        rejects("", "json.unexpected_end");
        rejects("tru", "json.unexpected_char");
    }

    @Test
    @DisplayName("SSO-T-json-020 byte input rejects BOM and invalid UTF-8")
    void bytesInput() {
        SsoException bom = assertThrows(SsoException.class, () -> Json.parse(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}));
        assertEquals("json.bom", bom.code());
        SsoException bad = assertThrows(SsoException.class, () -> Json.parse(new byte[]{'"', (byte) 0xC3, '"'}));
        assertEquals("json.bad_utf8", bad.code());
        SsoException overlong = assertThrows(SsoException.class, () -> Json.parse(new byte[]{'"', (byte) 0xC0, (byte) 0xAF, '"'}));
        assertEquals("json.bad_utf8", overlong.code());
        assertEquals("\u00e9", Json.parse("\"\u00e9\"".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("SSO-T-json-021 writer escapes specials and round-trips")
    void writerEscapes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("q", "a\"b\\c\n\t\u0001\u2028");
        m.put("n", null);
        m.put("l", List.of(1, 2L, true));
        String out = Json.write(m);
        assertEquals("{\"q\":\"a\\\"b\\\\c\\n\\t\\u0001\\u2028\",\"n\":null,\"l\":[1,2,true]}", out);
        Map<String, Object> back = Json.parseObject(out);
        assertEquals(m.get("q"), back.get("q"));
    }

    @Test
    @DisplayName("SSO-T-json-022 canonical writer sorts keys recursively without whitespace")
    void canonical() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1);
        inner.put("a", 2);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kty", "RSA");
        m.put("e", "AQAB");
        m.put("inner", inner);
        assertEquals("{\"e\":\"AQAB\",\"inner\":{\"a\":2,\"z\":1},\"kty\":\"RSA\"}", Json.writeCanonical(m));
    }

    @Test
    @DisplayName("SSO-T-json-023 writer rejects NaN, infinite, lone surrogate, non-string key, unknown type")
    void writerRejects() {
        assertEquals("json.non_finite", assertThrows(SsoException.class, () -> Json.write(Double.NaN)).code());
        assertEquals("json.non_finite", assertThrows(SsoException.class, () -> Json.write(Double.POSITIVE_INFINITY)).code());
        assertEquals("json.lone_surrogate", assertThrows(SsoException.class, () -> Json.write("\uD800")).code());
        Map<Object, Object> bad = new LinkedHashMap<>();
        bad.put(1, "x");
        assertEquals("json.non_string_key", assertThrows(SsoException.class, () -> Json.write(bad)).code());
        assertEquals("json.unsupported_type", assertThrows(SsoException.class, () -> Json.write(new Object())).code());
    }

    @Test
    @DisplayName("SSO-T-json-024 writer refuses cyclic or overly deep structures")
    void writerDepth() {
        List<Object> root = new ArrayList<>();
        List<Object> cur = root;
        for (int i = 0; i < 100; i++) {
            List<Object> next = new ArrayList<>();
            cur.add(next);
            cur = next;
        }
        assertEquals("json.too_deep", assertThrows(SsoException.class, () -> Json.write(root)).code());
    }

    @Test
    @DisplayName("SSO-T-json-025 typed accessors enforce types")
    void accessors() {
        Map<String, Object> m = Json.parseObject("{\"s\":\"x\",\"n\":5,\"b\":true,\"l\":[\"a\"]}");
        assertEquals("x", Json.requireString(m, "s"));
        assertEquals(5L, Json.optLong(m, "n"));
        assertEquals(Boolean.TRUE, Json.optBoolean(m, "b"));
        assertEquals(List.of("a"), Json.optStringList(m, "l"));
        assertEquals("json.type_mismatch", assertThrows(SsoException.class, () -> Json.optString(m, "n")).code());
        assertEquals("json.missing_member", assertThrows(SsoException.class, () -> Json.requireString(m, "zz")).code());
        assertEquals("json.expected_object", assertThrows(SsoException.class, () -> Json.parseObject("[1]")).code());
    }

    @Test
    @DisplayName("SSO-T-json-026 whitespace outside RFC 8259 set is rejected")
    void whitespace() {
        assertEquals(1L, Json.parse(" \t\r\n1 \n"));
        rejects("\u00a01", "json.unexpected_char");
        rejects("1\u000b", "json.trailing_data");
    }

    @Test
    @DisplayName("SSO-T-json-027 exception messages carry only the stable code")
    void messagesAreCodes() {
        SsoException e = assertThrows(SsoException.class, () -> Json.parse("{\"secret-value\":1,\"secret-value\":2}"));
        assertEquals("json.duplicate_key", e.getMessage());
    }

    @Test
    @DisplayName("SSO-T-json-028 negative zero and exponent forms parse as numbers")
    void numberForms() {
        assertEquals(0L, Json.parse("-0"));
        assertEquals(new BigDecimal("-1.25E-7"), Json.parse("-1.25e-7"));
        assertEquals(new BigDecimal("2.5E+10"), Json.parse("2.5E+10"));
    }
}
