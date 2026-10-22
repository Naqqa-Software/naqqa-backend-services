package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static com.naqqa.sso.webauthn.TestAuthenticator.AT;
import static com.naqqa.sso.webauthn.TestAuthenticator.BE;
import static com.naqqa.sso.webauthn.TestAuthenticator.BS;
import static com.naqqa.sso.webauthn.TestAuthenticator.ED;
import static com.naqqa.sso.webauthn.TestAuthenticator.UP;
import static com.naqqa.sso.webauthn.TestAuthenticator.UV;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticatorDataTest {
    private static String code(byte[] data) {
        return assertThrows(SsoException.class, () -> AuthenticatorData.parse(data)).code();
    }

    @Test
    @DisplayName("SSO-T-webauthn-050 parses flags, counter and attested credential data")
    void parse() {
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        a.aaguid = Bytes.random(16);
        byte[] raw = TestAuthenticator.authData("naqqa.com", UP | UV | BE | BS | AT, 0x01020304L, a.attestedCredentialData(), null);
        AuthenticatorData ad = AuthenticatorData.parse(raw);
        assertArrayEquals(Hashes.sha256(Bytes.utf8("naqqa.com")), ad.rpIdHash());
        assertTrue(ad.userPresent() && ad.userVerified() && ad.backupEligible() && ad.backupState());
        assertFalse(ad.hasExtensions());
        assertEquals(0x01020304L, ad.signCount());
        assertArrayEquals(a.credentialId, ad.attestedCredentialData().credentialId());
        assertArrayEquals(a.aaguid, ad.attestedCredentialData().aaguid());
        assertTrue(ad.attestedCredentialData().credentialPublicKey().sameKey(a.keys.getPublic()));
    }

    @Test
    @DisplayName("SSO-T-webauthn-051 unsigned 32-bit counter maximum parsed without sign error")
    void counterMax() {
        AuthenticatorData ad = AuthenticatorData.parse(TestAuthenticator.authData("naqqa.com", UP, 0xFFFFFFFFL, null, null));
        assertEquals(0xFFFFFFFFL, ad.signCount());
        assertNull(ad.attestedCredentialData());
    }

    @Test
    @DisplayName("SSO-T-webauthn-052 extensions parsed when ED set")
    void extensions() {
        byte[] ext = Cbor.encode(Map.of("credProtect", 2L));
        AuthenticatorData ad = AuthenticatorData.parse(TestAuthenticator.authData("naqqa.com", UP | ED, 1, null, ext));
        assertEquals(2L, ad.extensions().get("credProtect"));
    }

    @Test
    @DisplayName("SSO-T-webauthn-053 credential id longer than 1023 bytes rejected")
    void credentialIdTooLong() {
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        byte[] att = TestAuthenticator.attestedCredentialData(new byte[16], new byte[1024], a.coseKey().encoded());
        assertEquals("webauthn.authdata.credential_id_length", code(TestAuthenticator.authData("naqqa.com", UP | AT, 0, att, null)));
        byte[] ok = TestAuthenticator.attestedCredentialData(new byte[16], new byte[1023], a.coseKey().encoded());
        assertEquals(1023, AuthenticatorData.parse(TestAuthenticator.authData("naqqa.com", UP | AT, 0, ok, null))
                .attestedCredentialData().credentialId().length);
    }

    @Test
    @DisplayName("SSO-T-webauthn-054 trailing bytes after key without ED flag rejected")
    void trailing() {
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        byte[] raw = TestAuthenticator.authData("naqqa.com", UP | AT, 0, a.attestedCredentialData(), new byte[]{0x00});
        assertEquals("webauthn.authdata.trailing", code(raw));
        assertEquals("webauthn.authdata.trailing", code(TestAuthenticator.authData("naqqa.com", UP, 0, null, new byte[]{(byte) 0xa0})));
    }

    @Test
    @DisplayName("SSO-T-webauthn-055 truncated data and AT flag without data rejected")
    void truncated() {
        assertEquals("webauthn.authdata.truncated", code(new byte[36]));
        assertEquals("webauthn.authdata.truncated", code(TestAuthenticator.authData("naqqa.com", UP | AT, 0, null, null)));
        assertEquals("webauthn.authdata.truncated", code(TestAuthenticator.authData("naqqa.com", UP | ED, 0, null, null)));
        TestAuthenticator a = TestAuthenticator.create(CoseAlgorithm.ES256);
        byte[] full = TestAuthenticator.authData("naqqa.com", UP | AT, 0, a.attestedCredentialData(), null);
        assertThrows(SsoException.class, () -> AuthenticatorData.parse(Arrays.copyOf(full, full.length - 3)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-056 backup state without backup eligibility rejected")
    void bsWithoutBe() {
        assertEquals("webauthn.authdata.backup_state_invalid", code(TestAuthenticator.authData("naqqa.com", UP | BS, 0, null, null)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-057 COSE key inside authData with indefinite map rejected")
    void indefiniteKey() {
        byte[] att = Bytes.concat(new byte[16], new byte[]{0, 1, 7}, new byte[]{(byte) 0xbf, 0x01, 0x02, (byte) 0xff});
        assertEquals("webauthn.cbor.indefinite", code(TestAuthenticator.authData("naqqa.com", UP | AT, 0, att, null)));
    }

    @Test
    @DisplayName("SSO-T-webauthn-058 oversized authenticator data rejected")
    void tooLarge() {
        assertEquals("webauthn.authdata.too_large", code(new byte[AuthenticatorData.MAX_BYTES + 1]));
    }
}
