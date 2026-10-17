package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientDataTest {
    private static String code(String json) {
        return assertThrows(SsoException.class, () -> ClientData.parse(Bytes.utf8(json))).code();
    }

    @Test
    @DisplayName("SSO-T-webauthn-065 parses client data and hashes the exact bytes")
    void parse() {
        byte[] ch = Bytes.random(32);
        byte[] raw = Bytes.utf8("{\"type\":\"webauthn.get\",\"challenge\":\"" + Bytes.b64url(ch)
                + "\",\"origin\":\"https://app.naqqa.com\",\"crossOrigin\":false,\"other_keys_can_be_added_here\":\"x\"}");
        ClientData cd = ClientData.parse(raw);
        assertEquals("webauthn.get", cd.type());
        assertArrayEquals(ch, cd.challenge());
        assertFalse(cd.crossOrigin());
        assertArrayEquals(Hashes.sha256(raw), cd.hash());
    }

    @Test
    @DisplayName("SSO-T-webauthn-066 duplicate keys in client data rejected")
    void duplicate() {
        assertEquals("webauthn.client_data.invalid",
                code("{\"type\":\"webauthn.get\",\"type\":\"webauthn.create\",\"challenge\":\"AA\",\"origin\":\"x\"}"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-067 malformed JSON, trailing data and wrong types rejected")
    void malformed() {
        assertEquals("webauthn.client_data.invalid", code("{\"type\":\"webauthn.get\""));
        assertEquals("webauthn.client_data.invalid", code("{\"type\":\"webauthn.get\",\"challenge\":\"AA\",\"origin\":\"x\"} x"));
        assertEquals("webauthn.client_data.invalid", code("{\"type\":1,\"challenge\":\"AA\",\"origin\":\"x\"}"));
        assertEquals("webauthn.client_data.invalid", code("{\"type\":\"a\",\"challenge\":\"AA\",\"origin\":\"x\",\"crossOrigin\":\"no\"}"));
        assertEquals("webauthn.client_data.invalid", code("[]"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-068 non-canonical base64url challenge rejected")
    void badChallenge() {
        assertEquals("webauthn.client_data.invalid", code("{\"type\":\"a\",\"challenge\":\"AA==\",\"origin\":\"x\"}"));
        assertEquals("webauthn.client_data.invalid", code("{\"type\":\"a\",\"challenge\":\"AB\",\"origin\":\"x\"}"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-069 invalid UTF-8 and oversize client data rejected")
    void utf8AndSize() {
        assertEquals("webauthn.client_data.invalid", assertThrows(SsoException.class,
                () -> ClientData.parse(new byte[]{'{', (byte) 0xC3, '}'})).code());
        assertEquals("webauthn.client_data.invalid", assertThrows(SsoException.class,
                () -> ClientData.parse(new byte[ClientData.MAX_BYTES + 1])).code());
    }

    @Test
    @DisplayName("SSO-T-webauthn-070 JSON nesting depth limited")
    void depth() {
        String deep = "[".repeat(40) + "]".repeat(40);
        assertEquals("webauthn.client_data.invalid", code("{\"type\":\"a\",\"challenge\":\"AA\",\"origin\":\"x\",\"z\":" + deep + "}"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-071 token binding present rejected, crossOrigin true parsed")
    void tokenBindingAndCrossOrigin() {
        assertEquals("webauthn.client_data.token_binding_unsupported",
                code("{\"type\":\"a\",\"challenge\":\"AA\",\"origin\":\"x\",\"tokenBinding\":{\"status\":\"present\",\"id\":\"AA\"}}"));
        assertTrue(ClientData.parse(Bytes.utf8("{\"type\":\"a\",\"challenge\":\"AA\",\"origin\":\"x\",\"crossOrigin\":true}")).crossOrigin());
    }

    @Test
    @DisplayName("SSO-T-webauthn-072 JSON writer escapes quote and control characters")
    void writer() {
        String out = Json.write(java.util.Map.of("k", "a\"<b>\u0001"));
        assertEquals("{\"k\":\"a\\\"<b>\\u0001\"}", out);
        assertEquals("a\"<b>\u0001", Json.parseObject(out, WebAuthnLimits.JSON).get("k"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-210 F-L3 client data with duplicate keys is rejected by the strict parser")
    void duplicateKeys() {
        String ch = Bytes.b64url(Bytes.random(32));
        String dup = "{\"type\":\"webauthn.get\",\"type\":\"webauthn.create\",\"challenge\":\"" + ch + "\",\"origin\":\"https://a.example\"}";
        assertEquals("webauthn.client_data.invalid", code(dup));
    }

    @Test
    @DisplayName("SSO-T-webauthn-211 F-L3 client data nested deeper than the limit and trailing garbage are rejected")
    void depthAndTrailing() {
        String ch = Bytes.b64url(Bytes.random(32));
        String ok = "{\"type\":\"webauthn.get\",\"challenge\":\"" + ch + "\",\"origin\":\"https://a.example\"}";
        assertEquals("webauthn.client_data.invalid", code(ok + "x"));
        assertEquals("webauthn.client_data.invalid", code("{\"a\":" + "[".repeat(40) + "]".repeat(40) + "}"));
        assertEquals("webauthn.client_data.invalid", code(" ".repeat(70000) + ok));
    }
}
