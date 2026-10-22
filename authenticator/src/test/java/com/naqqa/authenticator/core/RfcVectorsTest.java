package com.naqqa.authenticator.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RfcVectorsTest {

    private static final byte[] SEED_SHA1 = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SEED_SHA256 = "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SEED_SHA512 =
            "1234567890123456789012345678901234567890123456789012345678901234".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
            "0,755224", "1,287082", "2,359152", "3,969429", "4,338314",
            "5,254676", "6,287922", "7,162583", "8,399871", "9,520489"
    })
    void hotpMatchesRfc4226AppendixD(long counter, String expected) {
        assertThat(Hotp.generate(SEED_SHA1, counter, 6, HmacAlgorithm.SHA1)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "59,94287082,46119246,90693936",
            "1111111109,07081804,68084774,25091201",
            "1111111111,14050471,67062674,99943326",
            "1234567890,89005924,91819424,93441116",
            "2000000000,69279037,90698825,38618901",
            "20000000000,65353130,77737706,47863826"
    })
    void totpMatchesRfc6238AppendixB(long epochSeconds, String sha1, String sha256, String sha512) {
        long millis = epochSeconds * 1000L;
        assertThat(Totp.generate(SEED_SHA1, millis, new TotpParameters(HmacAlgorithm.SHA1, 8, 30, 0))).isEqualTo(sha1);
        assertThat(Totp.generate(SEED_SHA256, millis, new TotpParameters(HmacAlgorithm.SHA256, 8, 30, 0))).isEqualTo(sha256);
        assertThat(Totp.generate(SEED_SHA512, millis, new TotpParameters(HmacAlgorithm.SHA512, 8, 30, 0))).isEqualTo(sha512);
    }

    @ParameterizedTest
    @CsvSource({
            "f,MY", "fo,MZXQ", "foo,MZXW6", "foob,MZXW6YQ", "fooba,MZXW6YTB", "foobar,MZXW6YTBOI"
    })
    void base32MatchesRfc4648WithoutPadding(String plain, String encoded) {
        assertThat(Base32.encode(plain.getBytes(StandardCharsets.US_ASCII))).isEqualTo(encoded);
        assertThat(new String(Base32.decode(encoded), StandardCharsets.US_ASCII)).isEqualTo(plain);
    }

    @Test
    void base32DecodeAcceptsLowercaseSpacesAndPadding() {
        assertThat(new String(Base32.decode("mzxw 6ytb oi======"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    }

    @Test
    void base32RejectsInvalidCharacters() {
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base32RoundTripsRandomSecrets() {
        byte[] secret = new byte[20];
        new java.security.SecureRandom().nextBytes(secret);
        assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
    }

    @Test
    void hotpRejectsUnsupportedDigitCounts() {
        assertThatThrownBy(() -> Hotp.generate(SEED_SHA1, 0, 5, HmacAlgorithm.SHA1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hotp.generate(SEED_SHA1, 0, 11, HmacAlgorithm.SHA1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void algorithmNamesAreParsedLeniently() {
        assertThat(HmacAlgorithm.fromName("sha-256")).isEqualTo(HmacAlgorithm.SHA256);
        assertThat(HmacAlgorithm.fromName("HmacSHA512")).isEqualTo(HmacAlgorithm.SHA512);
        assertThat(HmacAlgorithm.fromName("SHA1")).isEqualTo(HmacAlgorithm.SHA1);
        assertThatThrownBy(() -> HmacAlgorithm.fromName("MD5")).isInstanceOf(IllegalArgumentException.class);
    }
}
