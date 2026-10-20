package com.naqqa.acs.events;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AcsRequestSignerTest {

    @Test
    void signatureMatchesTheTypeScriptReferenceVector() throws Exception {
        byte[] key = AcsRequestSigner.hkdfSha256(AcsRequestSigner.base32Decode("a".repeat(52)),
                "acs-req-v1".getBytes(StandardCharsets.UTF_8), "req:abcdefghijklmnop".getBytes(StandardCharsets.UTF_8), 32);
        byte[] canonical = AcsRequestSigner.canonical("post", "/v1//events/./x/..", "b=2&a=%20x&a=!",
                "{\"events\":[]}".getBytes(StandardCharsets.UTF_8), 1760000000000L, "nonce-1234567890", "abcdefghijklmnop");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        assertEquals("uzJGW8X-Y5ANcrTA39s4zFVShxSYHWFcOZfIuCSag3w",
                Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical)));
    }

    @Test
    void invalidSecretKeysAreRefused() {
        assertNull(AcsRequestSigner.fromSecretKey(null));
        assertNull(AcsRequestSigner.fromSecretKey("acs_pk_test_abcdefghijklmnop_" + "a".repeat(52) + "_00000000"));
        assertNull(AcsRequestSigner.fromSecretKey("acs_sk_test_abcdefghijklmnop_" + "a".repeat(52) + "_00000000"));
    }
}
