package com.naqqa.authenticator.service;

import com.naqqa.authenticator.core.Base32;
import com.naqqa.authenticator.core.Hotp;
import com.naqqa.authenticator.core.RecoveryCodes;
import com.naqqa.authenticator.core.SecretCipher;
import com.naqqa.authenticator.core.Tokens;
import com.naqqa.authenticator.core.Totp;
import com.naqqa.authenticator.core.TotpParameters;
import com.naqqa.authenticator.domain.AuthenticatorEvent;
import com.naqqa.authenticator.domain.ChallengeTicket;
import com.naqqa.authenticator.domain.MfaFactor;
import com.naqqa.authenticator.domain.MfaMethod;
import com.naqqa.authenticator.domain.TotpEnrollmentStart;
import com.naqqa.authenticator.domain.VerifiedChallenge;
import com.naqqa.authenticator.store.InMemoryMfaStore;
import com.naqqa.commons.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatorServiceTest {

    private static final String USER = "42";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:05Z"));
    private final List<AuthenticatorEvent> events = new ArrayList<>();
    private InMemoryMfaStore store;
    private AuthenticatorService service;

    @BeforeEach
    void setUp() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        SecretCipher cipher = new SecretCipher(key, random);
        store = new InMemoryMfaStore();
        service = new AuthenticatorService(store, cipher, new RecoveryCodes(cipher.deriveKey("pepper"), random),
                new Tokens(random), AuthenticatorSettings.defaults(), clock, events::add);
    }

    private static String codeAt(String base32, Instant instant) {
        return Totp.generate(Base32.decode(base32), instant.toEpochMilli(), TotpParameters.DEFAULT);
    }

    private static String codeForCounter(String base32, long counter) {
        return Hotp.generate(Base32.decode(base32), counter, 6, TotpParameters.DEFAULT.algorithm());
    }

    private String enroll() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana@naqqa.dev");
        service.confirmTotpEnrollment(USER, start.enrollmentId(), codeAt(start.secret(), clock.instant()));
        return start.secret();
    }

    private void nextStep() {
        clock.advance(Duration.ofSeconds(30));
    }

    @Test
    void enrollmentReturnsScannableUriAndConfirmIssuesRecoveryCodes() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana@naqqa.dev");

        assertThat(start.otpauthUri()).startsWith("otpauth://totp/naqqa:ana%40naqqa.dev?secret=" + start.secret());
        assertThat(Base32.decode(start.secret())).hasSize(20);
        assertThat(service.hasActiveFactor(USER)).isFalse();

        List<String> codes = service.confirmTotpEnrollment(USER, start.enrollmentId(), codeAt(start.secret(), clock.instant()));

        assertThat(codes).hasSize(10);
        assertThat(service.hasActiveFactor(USER)).isTrue();
        assertThat(service.methodsFor(USER)).containsExactly("totp", "recovery");
        List<MfaFactor> factors = service.listFactors(USER);
        assertThat(factors).hasSize(1);
        assertThat(factors.get(0).secretCiphertext()).doesNotContain(start.secret());
        assertThat(events).extracting(AuthenticatorEvent::type).contains(AuthenticatorEvent.Type.TOTP_ENROLLED);
    }

    @Test
    void confirmWithWrongCodeFailsAndLocksTheSetupAfterMaxAttempts() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.confirmTotpEnrollment(USER, start.enrollmentId(), "000000"))
                    .satisfies(ex -> assertThat(((ApiException) ex).code()).isIn("OTP_INVALID"));
        }
        assertThatThrownBy(() -> service.confirmTotpEnrollment(USER, start.enrollmentId(), "000000"))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("SETUP_EXPIRED"));
        String good = codeAt(start.secret(), clock.instant());
        assertThatThrownBy(() -> service.confirmTotpEnrollment(USER, start.enrollmentId(), good))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("SETUP_EXPIRED"));
    }

    @Test
    void enrollmentExpiresAfterTtl() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana");
        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> service.confirmTotpEnrollment(USER, start.enrollmentId(), codeAt(start.secret(), clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("SETUP_EXPIRED"));
    }

    @Test
    void anotherUserCannotConfirmSomeoneElsesEnrollment() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana");

        assertThatThrownBy(() -> service.confirmTotpEnrollment("7", start.enrollmentId(), codeAt(start.secret(), clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("SETUP_EXPIRED"));
    }

    @Test
    void secondEnrollmentIsRejectedWhileAFactorExists() {
        enroll();

        assertThatThrownBy(() -> service.beginTotpEnrollment(USER, "ana"))
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).code()).isEqualTo("TOTP_ALREADY_ENROLLED");
                    assertThat(((ApiException) ex).status()).isEqualTo(409);
                });
    }

    @Test
    void loginChallengeVerifiesTotpAndCarriesContext() {
        String secret = enroll();
        nextStep();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of("deviceId", "d1"));

        VerifiedChallenge verified = service.verify(ticket.mfaToken(), "totp", codeAt(secret, clock.instant()));

        assertThat(ticket.methods()).containsExactly("totp", "recovery");
        assertThat(verified.userId()).isEqualTo(USER);
        assertThat(verified.method()).isEqualTo(MfaMethod.TOTP);
        assertThat(verified.context()).containsEntry("deviceId", "d1");
        assertThat(service.listFactors(USER).get(0).lastUsedAt()).isEqualTo(clock.instant());
    }

    @Test
    void codeUsedAtEnrollmentCannotBeReplayedAtLogin() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana");
        String code = codeAt(start.secret(), clock.instant());
        service.confirmTotpEnrollment(USER, start.enrollmentId(), code);
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());

        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", code))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("OTP_INVALID"));
        assertThat(events).extracting(AuthenticatorEvent::type).contains(AuthenticatorEvent.Type.REPLAY_REJECTED);
    }

    @Test
    void alreadyUsedTotpCodeIsRejectedOnTheNextLogin() {
        String secret = enroll();
        nextStep();
        String code = codeAt(secret, clock.instant());
        service.verify(service.beginChallenge(USER, Map.of()).mfaToken(), "totp", code);

        ChallengeTicket second = service.beginChallenge(USER, Map.of());

        assertThatThrownBy(() -> service.verify(second.mfaToken(), "totp", code))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("OTP_INVALID"));
    }

    @Test
    void olderCodeIsRejectedOnceANewerCounterWasUsed() {
        String secret = enroll();
        nextStep();
        nextStep();
        long current = Totp.counterAt(clock.instant().toEpochMilli(), 30);
        service.verify(service.beginChallenge(USER, Map.of()).mfaToken(), "totp", codeForCounter(secret, current + 1));

        ChallengeTicket again = service.beginChallenge(USER, Map.of());

        assertThatThrownBy(() -> service.verify(again.mfaToken(), "totp", codeForCounter(secret, current)))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("OTP_INVALID"));
    }

    @Test
    void challengeTokenIsSingleUse() {
        String secret = enroll();
        nextStep();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());
        service.verify(ticket.mfaToken(), "totp", codeAt(secret, clock.instant()));
        nextStep();

        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", codeAt(secret, clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
    }

    @Test
    void challengeExpires() {
        String secret = enroll();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());
        clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", codeAt(secret, clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
    }

    @Test
    void challengeLocksAfterTooManyWrongCodes() {
        String secret = enroll();
        nextStep();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", "000000"))
                    .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("OTP_INVALID"));
        }

        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", codeAt(secret, clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(429));
        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", codeAt(secret, clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
    }

    @Test
    void newChallengeSupersedesThePreviousOne() {
        String secret = enroll();
        nextStep();
        ChallengeTicket first = service.beginChallenge(USER, Map.of());
        ChallengeTicket second = service.beginChallenge(USER, Map.of());

        assertThatThrownBy(() -> service.verify(first.mfaToken(), "totp", codeAt(secret, clock.instant())))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
        assertThat(service.verify(second.mfaToken(), "totp", codeAt(secret, clock.instant())).userId()).isEqualTo(USER);
    }

    @Test
    void recoveryCodeWorksOnceAndIsNormalized() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana");
        List<String> codes = service.confirmTotpEnrollment(USER, start.enrollmentId(), codeAt(start.secret(), clock.instant()));
        String code = codes.get(3);

        VerifiedChallenge verified = service.verify(service.beginChallenge(USER, Map.of()).mfaToken(), "recovery",
                code.toUpperCase().replace("-", " "));

        assertThat(verified.method()).isEqualTo(MfaMethod.RECOVERY);
        ChallengeTicket again = service.beginChallenge(USER, Map.of());
        assertThatThrownBy(() -> service.verify(again.mfaToken(), "recovery", code))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("OTP_INVALID"));
        assertThat(store.countRecoveryCodes(USER)).isEqualTo(9);
    }

    @Test
    void regeneratingRecoveryCodesInvalidatesTheOldSet() {
        TotpEnrollmentStart start = service.beginTotpEnrollment(USER, "ana");
        List<String> old = service.confirmTotpEnrollment(USER, start.enrollmentId(), codeAt(start.secret(), clock.instant()));

        List<String> fresh = service.regenerateRecoveryCodes(USER);

        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(old);
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());
        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "recovery", old.get(0)))
                .isInstanceOf(ApiException.class);
        assertThat(service.verify(ticket.mfaToken(), "recovery", fresh.get(0)).userId()).isEqualTo(USER);
    }

    @Test
    void regenerateWithoutAFactorIsUnavailable() {
        assertThatThrownBy(() -> service.regenerateRecoveryCodes(USER))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("METHOD_UNAVAILABLE"));
    }

    @Test
    void unknownMethodIsUnavailable() {
        enroll();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());

        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "sms_otp", "123456"))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("METHOD_UNAVAILABLE"));
        assertThatThrownBy(() -> service.prepareChallenge(ticket.mfaToken(), "push"))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("METHOD_UNAVAILABLE"));
        service.prepareChallenge(ticket.mfaToken(), "totp");
    }

    @Test
    void forgedTokensAreRejected() {
        enroll();

        assertThatThrownBy(() -> service.verify("forged", "totp", "123456"))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
        assertThatThrownBy(() -> service.verify(null, "totp", "123456"))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
    }

    @Test
    void challengeRequiresAnEnrolledFactor() {
        assertThatThrownBy(() -> service.beginChallenge(USER, Map.of()))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("METHOD_UNAVAILABLE"));
    }

    @Test
    void removingTheLastFactorClearsRecoveryCodesAndPendingChallenges() {
        enroll();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());
        String factorId = service.listFactors(USER).get(0).id();

        service.removeFactor(USER, factorId);

        assertThat(service.hasActiveFactor(USER)).isFalse();
        assertThat(store.countRecoveryCodes(USER)).isZero();
        assertThatThrownBy(() -> service.verify(ticket.mfaToken(), "totp", "123456"))
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("MFA_TOKEN_EXPIRED"));
    }

    @Test
    void usersCannotRemoveOtherUsersFactors() {
        enroll();
        String factorId = service.listFactors(USER).get(0).id();

        assertThatThrownBy(() -> service.removeFactor("7", factorId))
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(404));
        assertThat(service.hasActiveFactor(USER)).isTrue();
    }

    @Test
    void adminResetRemovesEverything() {
        enroll();
        ChallengeTicket ticket = service.beginChallenge(USER, Map.of());

        service.resetFactors(USER);

        assertThat(service.hasActiveFactor(USER)).isFalse();
        assertThat(service.methodsFor(USER)).isEmpty();
        assertThat(store.countRecoveryCodes(USER)).isZero();
        assertThat(store.findChallenge(Tokens.sha256Hex(ticket.mfaToken()))).isEmpty();
        assertThat(events).extracting(AuthenticatorEvent::type).contains(AuthenticatorEvent.Type.FACTORS_RESET);
        service.beginTotpEnrollment(USER, "ana");
    }

    @Test
    void blankUserIsUnauthorized() {
        assertThatThrownBy(() -> service.beginTotpEnrollment(" ", "x"))
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(401));
    }

    @Test
    void failingEventListenerNeverBreaksTheFlow() {
        SecureRandom random = new SecureRandom();
        SecretCipher cipher = new SecretCipher(new byte[32], random);
        AuthenticatorService noisy = new AuthenticatorService(new InMemoryMfaStore(), cipher,
                new RecoveryCodes(cipher.deriveKey("p"), random), new Tokens(random), AuthenticatorSettings.defaults(),
                clock, event -> {
                    throw new IllegalStateException("audit down");
                });

        TotpEnrollmentStart start = noisy.beginTotpEnrollment(USER, "ana");

        assertThat(noisy.confirmTotpEnrollment(USER, start.enrollmentId(), codeAt(start.secret(), clock.instant()))).hasSize(10);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
