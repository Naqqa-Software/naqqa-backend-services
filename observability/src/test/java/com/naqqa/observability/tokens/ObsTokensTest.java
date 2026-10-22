package com.naqqa.observability.tokens;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class ObsTokensTest {

    private final ObsTokens tokens = new ObsTokens(new byte[32], new SecureRandom());

    @Test
    void crcIsTheStandardIeeeCrc32UsedByTheFrontend() {
        assertThat(ObsTokens.crc32("123456789")).isEqualTo("cbf43926");
        assertThat(ObsTokens.crc32("")).isEqualTo("00000000");
    }

    @Test
    void mintedTokensMatchTheFrontendFormatAndParseBack() {
        ObsTokens.Minted minted = tokens.mint(ObsTokens.INGEST, "live");

        assertThat(minted.raw()).matches("^obs_ing_live_[a-z2-7]{16}_[a-z2-7]{40}_[0-9a-f]{8}$");
        ObsTokens.Parsed parsed = ObsTokens.parse(minted.raw()).orElseThrow();
        assertThat(parsed.kind()).isEqualTo("ing");
        assertThat(parsed.env()).isEqualTo("live");
        assertThat(parsed.id()).isEqualTo(minted.id());
    }

    @Test
    void tamperedOrMalformedTokensAreRejectedBeforeAnyLookup() {
        String raw = tokens.mint(ObsTokens.MANAGEMENT, "test").raw();
        char c = raw.charAt(30);
        String tampered = raw.substring(0, 30) + (c == 'a' ? 'b' : 'a') + raw.substring(31);

        assertThat(ObsTokens.parse(tampered)).isEmpty();
        assertThat(ObsTokens.parse("obs_ing_live_short")).isEmpty();
        assertThat(ObsTokens.parse(null)).isEmpty();
        assertThat(ObsTokens.parse(raw.toUpperCase())).isEmpty();
    }

    @Test
    void hashesArePepperedAndCompareInConstantTime() {
        String raw = tokens.mint(ObsTokens.INGEST, "live").raw();
        String hash = tokens.hash(raw);

        assertThat(hash).hasSize(64).doesNotContain(raw);
        assertThat(tokens.matches(raw, hash)).isTrue();
        assertThat(tokens.matches(raw + "x", hash)).isFalse();
        ObsTokens other = new ObsTokens(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}, new SecureRandom());
        assertThat(other.matches(raw, hash)).isFalse();
    }

    @Test
    void maskKeepsOnlyThePrefixAndIdHint() {
        ObsTokens.Minted minted = tokens.mint(ObsTokens.INGEST, "live");

        assertThat(ObsTokens.mask(minted.raw())).startsWith("obs_ing_live_" + minted.id().substring(0, 4)).doesNotContain(minted.id());
        assertThat(ObsTokens.mask("garbage")).isEmpty();
    }
}
