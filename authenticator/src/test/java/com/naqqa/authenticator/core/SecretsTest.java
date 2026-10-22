package com.naqqa.authenticator.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretCipher cipher() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new SecretCipher(key, RANDOM);
    }

    @Test
    void cipherRoundTripsAndUsesFreshNonces() {
        SecretCipher cipher = cipher();
        byte[] secret = "top secret".getBytes(StandardCharsets.UTF_8);

        String first = cipher.encrypt(secret, "ctx");
        String second = cipher.encrypt(secret, "ctx");

        assertThat(first).startsWith("v1.").isNotEqualTo(second);
        assertThat(cipher.decrypt(first, "ctx")).isEqualTo(secret);
    }

    @Test
    void cipherRejectsWrongContextTamperingAndWrongKey() {
        SecretCipher cipher = cipher();
        String sealed = cipher.encrypt("abc".getBytes(StandardCharsets.UTF_8), "user:1");
        int at = sealed.lastIndexOf('.') + 2;
        char original = sealed.charAt(at);
        String tampered = sealed.substring(0, at) + (original == 'A' ? 'B' : 'A') + sealed.substring(at + 1);

        assertThatThrownBy(() -> cipher.decrypt(sealed, "user:2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(tampered, "user:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher().decrypt(sealed, "user:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt("plain", "user:1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cipherRequiresA32ByteBase64Key() {
        assertThatThrownBy(() -> SecretCipher.fromBase64(null, RANDOM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCipher.fromBase64("not base64!", RANDOM)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[16]), RANDOM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SecretCipher.fromBase64(Base64.getEncoder().encodeToString(new byte[32]), RANDOM)).isNotNull();
    }

    @Test
    void derivedKeysAreStableAndLabelSpecific() {
        SecretCipher cipher = cipher();

        assertThat(cipher.deriveKey("a")).isEqualTo(cipher.deriveKey("a")).isNotEqualTo(cipher.deriveKey("b"));
    }

    @Test
    void recoveryCodesAreUniqueFormattedAndHashedPerUser() {
        RecoveryCodes codes = new RecoveryCodes(new byte[32], RANDOM);

        List<String> generated = codes.generate(10);

        assertThat(generated).hasSize(10);
        assertThat(new HashSet<>(generated)).hasSize(10);
        assertThat(generated).allMatch(code -> code.matches("[a-z2-9]{4}-[a-z2-9]{4}"));
        String code = generated.get(0);
        assertThat(codes.hash("1", code)).isEqualTo(codes.hash("1", code.toUpperCase().replace("-", " ")));
        assertThat(codes.hash("1", code)).isNotEqualTo(codes.hash("2", code));
    }

    @Test
    void recoveryCodesRequireAPepper() {
        assertThatThrownBy(() -> new RecoveryCodes(new byte[4], RANDOM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opaqueTokensAreUrlSafeAndHashedDeterministically() {
        Tokens tokens = new Tokens(RANDOM);

        String token = tokens.opaque(32);

        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Tokens.sha256Hex(token)).hasSize(64).isEqualTo(Tokens.sha256Hex(token));
        assertThat(tokens.id("fac")).startsWith("fac_");
    }
}
