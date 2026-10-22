package com.naqqa.registry.core;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryTokensTest {

    private final RegistryTokens tokens = new RegistryTokens(new byte[32], new SecureRandom());

    @Test
    void mintedTokensParseAndCarryTheirId() {
        RegistryTokens.Minted minted = tokens.mint();
        assertThat(minted.raw()).startsWith("nqr_" + minted.id() + "_").hasSize(4 + 16 + 1 + 40 + 1 + 8);
        assertThat(RegistryTokens.parse(minted.raw())).hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(minted.id()));
        assertThat(RegistryTokens.parse("  " + minted.raw() + "  ")).isPresent();
    }

    @Test
    void mintingIsUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(seen.add(tokens.mint().raw())).isTrue();
        }
    }

    @Test
    void checksumRejectsTypos() {
        String raw = tokens.mint().raw();
        int i = 10;
        char swapped = raw.charAt(i) == 'a' ? 'b' : 'a';
        String typo = raw.substring(0, i) + swapped + raw.substring(i + 1);
        assertThat(RegistryTokens.parse(typo)).isEmpty();
        assertThat(RegistryTokens.parse(null)).isEmpty();
        assertThat(RegistryTokens.parse("nqr_short")).isEmpty();
        assertThat(RegistryTokens.parse(raw.toUpperCase())).isEmpty();
        assertThat(RegistryTokens.parse(raw + "x".repeat(200))).isEmpty();
    }

    @Test
    void hashIsKeyedAndComparedInConstantTime() {
        String raw = tokens.mint().raw();
        String hash = tokens.hash(raw);
        assertThat(hash).hasSize(64).doesNotContain(raw);
        assertThat(tokens.matches(raw, hash)).isTrue();
        assertThat(tokens.matches(tokens.mint().raw(), hash)).isFalse();
        assertThat(tokens.matches(null, hash)).isFalse();
        assertThat(tokens.matches(raw, null)).isFalse();
        byte[] otherPepper = new byte[32];
        otherPepper[0] = 1;
        assertThat(new RegistryTokens(otherPepper, new SecureRandom()).hash(raw)).isNotEqualTo(hash);
    }

    @Test
    void maskNeverShowsTheSecret() {
        RegistryTokens.Minted minted = tokens.mint();
        String secret = RegistryTokens.parse(minted.raw()).orElseThrow().secret();
        assertThat(RegistryTokens.mask(minted.id())).isEqualTo("nqr_" + minted.id() + "_****").doesNotContain(secret);
    }

    @Test
    void shortPepperIsRejected() {
        assertThatThrownBy(() -> new RegistryTokens(new byte[8], new SecureRandom())).isInstanceOf(IllegalArgumentException.class);
    }
}
