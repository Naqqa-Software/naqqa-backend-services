package com.naqqa.authenticator.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TotpMatchTest {

    private static final byte[] KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final TotpParameters SIX = new TotpParameters(HmacAlgorithm.SHA1, 6, 30, 1);
    private static final long NOW = 1_700_000_015_000L;

    @Test
    void currentCodeMatchesCurrentCounter() {
        String code = Totp.generate(KEY, NOW, SIX);

        OptionalLong counter = Totp.match(KEY, code, NOW, SIX);

        assertThat(counter).hasValue(Totp.counterAt(NOW, 30));
    }

    @Test
    void previousAndNextStepAreAcceptedWithinSkew() {
        long step = Totp.counterAt(NOW, 30);
        String previous = Hotp.generate(KEY, step - 1, 6, HmacAlgorithm.SHA1);
        String next = Hotp.generate(KEY, step + 1, 6, HmacAlgorithm.SHA1);

        assertThat(Totp.match(KEY, previous, NOW, SIX)).hasValue(step - 1);
        assertThat(Totp.match(KEY, next, NOW, SIX)).hasValue(step + 1);
    }

    @Test
    void codesOutsideTheSkewWindowAreRejected() {
        long step = Totp.counterAt(NOW, 30);
        String old = Hotp.generate(KEY, step - 2, 6, HmacAlgorithm.SHA1);
        String future = Hotp.generate(KEY, step + 2, 6, HmacAlgorithm.SHA1);

        assertThat(Totp.match(KEY, old, NOW, SIX)).isEmpty();
        assertThat(Totp.match(KEY, future, NOW, SIX)).isEmpty();
    }

    @Test
    void zeroSkewOnlyAcceptsTheCurrentStep() {
        TotpParameters strict = new TotpParameters(HmacAlgorithm.SHA1, 6, 30, 0);
        long step = Totp.counterAt(NOW, 30);

        assertThat(Totp.match(KEY, Hotp.generate(KEY, step - 1, 6, HmacAlgorithm.SHA1), NOW, strict)).isEmpty();
        assertThat(Totp.match(KEY, Hotp.generate(KEY, step, 6, HmacAlgorithm.SHA1), NOW, strict)).hasValue(step);
    }

    @Test
    void malformedCodesNeverMatch() {
        assertThat(Totp.match(KEY, null, NOW, SIX)).isEmpty();
        assertThat(Totp.match(KEY, "", NOW, SIX)).isEmpty();
        assertThat(Totp.match(KEY, "12345", NOW, SIX)).isEmpty();
        assertThat(Totp.match(KEY, "1234567", NOW, SIX)).isEmpty();
        assertThat(Totp.match(KEY, "12a456", NOW, SIX)).isEmpty();
    }

    @Test
    void spacesAndDashesInTypedCodesAreIgnored() {
        String code = Totp.generate(KEY, NOW, SIX);
        String typed = code.substring(0, 3) + " " + code.substring(3);
        String dashed = code.substring(0, 3) + "-" + code.substring(3);

        assertThat(Totp.match(KEY, typed, NOW, SIX)).isPresent();
        assertThat(Totp.match(KEY, dashed, NOW, SIX)).isPresent();
    }

    @Test
    void otpAuthUriCarriesAllParameters() {
        String uri = OtpAuthUri.totp("naqqa OS", "ana@naqqa.dev", "JBSWY3DPEHPK3PXP", SIX);

        assertThat(uri).isEqualTo("otpauth://totp/naqqa%20OS:ana%40naqqa.dev?secret=JBSWY3DPEHPK3PXP"
                + "&issuer=naqqa%20OS&algorithm=SHA1&digits=6&period=30");
    }
}
