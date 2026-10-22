package com.naqqa.authenticator.service;

import com.naqqa.authenticator.core.Base32;
import com.naqqa.authenticator.core.OtpAuthUri;
import com.naqqa.authenticator.core.RecoveryCodes;
import com.naqqa.authenticator.core.SecretCipher;
import com.naqqa.authenticator.core.Tokens;
import com.naqqa.authenticator.core.Totp;
import com.naqqa.authenticator.core.TotpParameters;
import com.naqqa.authenticator.domain.AuthenticatorEvent;
import com.naqqa.authenticator.domain.ChallengeTicket;
import com.naqqa.authenticator.domain.FactorType;
import com.naqqa.authenticator.domain.MfaChallenge;
import com.naqqa.authenticator.domain.MfaEnrollment;
import com.naqqa.authenticator.domain.MfaFactor;
import com.naqqa.authenticator.domain.MfaMethod;
import com.naqqa.authenticator.domain.TotpEnrollmentStart;
import com.naqqa.authenticator.domain.VerifiedChallenge;
import com.naqqa.authenticator.spi.AuthenticatorEventListener;
import com.naqqa.authenticator.spi.MfaStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public class AuthenticatorService {

    private static final int MAX_CODE_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 128;

    private final MfaStore store;
    private final SecretCipher cipher;
    private final RecoveryCodes recoveryCodes;
    private final Tokens tokens;
    private final AuthenticatorSettings settings;
    private final Clock clock;
    private final AuthenticatorEventListener events;

    public AuthenticatorService(MfaStore store, SecretCipher cipher, RecoveryCodes recoveryCodes, Tokens tokens,
                                AuthenticatorSettings settings, Clock clock, AuthenticatorEventListener events) {
        this.store = store;
        this.cipher = cipher;
        this.recoveryCodes = recoveryCodes;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        this.events = events == null ? AuthenticatorEventListener.NO_OP : events;
    }

    public TotpEnrollmentStart beginTotpEnrollment(String userId, String accountName) {
        requireUser(userId);
        if (totpFactor(userId).isPresent()) {
            throw AuthenticatorErrors.alreadyEnrolled();
        }
        Instant now = clock.instant();
        byte[] secret = tokens.randomBytes(settings.secretBytes());
        try {
            TotpParameters parameters = settings.totp();
            MfaEnrollment enrollment = new MfaEnrollment(
                    tokens.id("enr"),
                    userId,
                    cipher.encrypt(secret, secretContext(userId)),
                    parameters.algorithm(),
                    parameters.digits(),
                    parameters.periodSeconds(),
                    0,
                    now,
                    now.plus(settings.enrollmentTtl()));
            store.deleteEnrollments(userId);
            store.saveEnrollment(enrollment);
            String base32 = Base32.encode(secret);
            String uri = OtpAuthUri.totp(settings.issuer(), accountName, base32, parameters);
            return new TotpEnrollmentStart(enrollment.id(), uri, base32, enrollment.expiresAt());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public List<String> confirmTotpEnrollment(String userId, String enrollmentId, String code) {
        requireUser(userId);
        requireCode(code);
        if (enrollmentId == null || enrollmentId.isBlank() || enrollmentId.length() > MAX_TOKEN_LENGTH) {
            throw AuthenticatorErrors.setupExpired();
        }
        Instant now = clock.instant();
        MfaEnrollment enrollment = store.findEnrollment(enrollmentId)
                .filter(candidate -> candidate.userId().equals(userId))
                .orElseThrow(AuthenticatorErrors::setupExpired);
        if (enrollment.isExpired(now)) {
            store.deleteEnrollment(enrollment.id());
            throw AuthenticatorErrors.setupExpired();
        }
        TotpParameters parameters = new TotpParameters(enrollment.algorithm(), enrollment.digits(),
                enrollment.periodSeconds(), settings.totp().skewSteps());
        byte[] secret = cipher.decrypt(enrollment.secretCiphertext(), secretContext(userId));
        OptionalLong counter;
        try {
            counter = Totp.match(secret, code, now.toEpochMilli(), parameters);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (counter.isEmpty()) {
            int attempts = enrollment.attempts() + 1;
            if (attempts >= settings.maxAttempts()) {
                store.deleteEnrollment(enrollment.id());
                throw AuthenticatorErrors.setupExpired();
            }
            store.saveEnrollment(enrollment.withAttempts(attempts));
            throw AuthenticatorErrors.otpInvalid();
        }
        if (totpFactor(userId).isPresent()) {
            store.deleteEnrollment(enrollment.id());
            throw AuthenticatorErrors.alreadyEnrolled();
        }
        MfaFactor factor = new MfaFactor(
                tokens.id("fac"),
                userId,
                FactorType.TOTP,
                settings.factorLabel(),
                enrollment.secretCiphertext(),
                enrollment.algorithm(),
                enrollment.digits(),
                enrollment.periodSeconds(),
                counter.getAsLong(),
                now,
                null);
        store.saveFactor(factor);
        store.deleteEnrollment(enrollment.id());
        List<String> codes = issueRecoveryCodes(userId);
        emit(AuthenticatorEvent.Type.TOTP_ENROLLED, userId, factor.id());
        return codes;
    }

    public List<MfaFactor> listFactors(String userId) {
        requireUser(userId);
        return store.findFactors(userId);
    }

    public void removeFactor(String userId, String factorId) {
        requireUser(userId);
        if (factorId == null || factorId.isBlank() || !store.deleteFactor(userId, factorId)) {
            throw AuthenticatorErrors.notFound();
        }
        if (store.findFactors(userId).isEmpty()) {
            store.deleteRecoveryCodes(userId);
            store.deleteChallenges(userId);
        }
        emit(AuthenticatorEvent.Type.FACTOR_REMOVED, userId, factorId);
    }

    public List<String> regenerateRecoveryCodes(String userId) {
        requireUser(userId);
        if (!hasActiveFactor(userId)) {
            throw AuthenticatorErrors.methodUnavailable();
        }
        List<String> codes = issueRecoveryCodes(userId);
        emit(AuthenticatorEvent.Type.RECOVERY_CODES_REGENERATED, userId, null);
        return codes;
    }

    public boolean hasActiveFactor(String userId) {
        return userId != null && !userId.isBlank() && !store.findFactors(userId).isEmpty();
    }

    public List<String> methodsFor(String userId) {
        List<String> methods = new ArrayList<>();
        if (totpFactor(userId).isPresent()) {
            methods.add(MfaMethod.TOTP.wireName());
            if (store.countRecoveryCodes(userId) > 0) {
                methods.add(MfaMethod.RECOVERY.wireName());
            }
        }
        return methods;
    }

    public ChallengeTicket beginChallenge(String userId, Map<String, String> context) {
        requireUser(userId);
        List<String> methods = methodsFor(userId);
        if (methods.isEmpty()) {
            throw AuthenticatorErrors.methodUnavailable();
        }
        Instant now = clock.instant();
        String token = tokens.opaque(32);
        MfaChallenge challenge = new MfaChallenge(Tokens.sha256Hex(token), userId, context, 0, now,
                now.plus(settings.challengeTtl()));
        store.deleteChallenges(userId);
        store.saveChallenge(challenge);
        emit(AuthenticatorEvent.Type.CHALLENGE_STARTED, userId, null);
        return new ChallengeTicket(token, methods, challenge.expiresAt());
    }

    public void prepareChallenge(String mfaToken, String method) {
        MfaChallenge challenge = activeChallenge(mfaToken);
        MfaMethod parsed = MfaMethod.fromWire(method).orElseThrow(AuthenticatorErrors::methodUnavailable);
        if (!methodsFor(challenge.userId()).contains(parsed.wireName())) {
            throw AuthenticatorErrors.methodUnavailable();
        }
    }

    public VerifiedChallenge verify(String mfaToken, String method, String code) {
        MfaChallenge challenge = activeChallenge(mfaToken);
        MfaMethod parsed = MfaMethod.fromWire(method).orElseThrow(AuthenticatorErrors::methodUnavailable);
        String userId = challenge.userId();
        if (!methodsFor(userId).contains(parsed.wireName())) {
            throw AuthenticatorErrors.methodUnavailable();
        }
        int attempts = store.incrementAttempts(challenge.id());
        if (attempts > settings.maxAttempts()) {
            store.deleteChallenge(challenge.id());
            emit(AuthenticatorEvent.Type.CHALLENGE_LOCKED, userId, null);
            throw AuthenticatorErrors.rateLimited();
        }
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            emit(AuthenticatorEvent.Type.VERIFY_FAILED, userId, null);
            throw AuthenticatorErrors.otpInvalid();
        }
        if (parsed == MfaMethod.TOTP) {
            verifyTotp(userId, code);
        } else {
            verifyRecovery(userId, code);
        }
        if (!store.deleteChallenge(challenge.id())) {
            throw AuthenticatorErrors.tokenExpired();
        }
        emit(AuthenticatorEvent.Type.VERIFY_SUCCEEDED, userId, null);
        return new VerifiedChallenge(userId, parsed, challenge.context());
    }

    public void resetFactors(String userId) {
        requireUser(userId);
        store.deleteFactors(userId);
        store.deleteRecoveryCodes(userId);
        store.deleteEnrollments(userId);
        store.deleteChallenges(userId);
        emit(AuthenticatorEvent.Type.FACTORS_RESET, userId, null);
    }

    private void verifyTotp(String userId, String code) {
        MfaFactor factor = totpFactor(userId).orElseThrow(AuthenticatorErrors::methodUnavailable);
        Instant now = clock.instant();
        byte[] secret = cipher.decrypt(factor.secretCiphertext(), secretContext(userId));
        OptionalLong counter;
        try {
            counter = Totp.match(secret, code, now.toEpochMilli(), factor.parameters(settings.totp().skewSteps()));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (counter.isEmpty()) {
            emit(AuthenticatorEvent.Type.VERIFY_FAILED, userId, factor.id());
            throw AuthenticatorErrors.otpInvalid();
        }
        if (counter.getAsLong() <= factor.lastUsedCounter() || !store.recordUse(factor.id(), counter.getAsLong(), now)) {
            emit(AuthenticatorEvent.Type.REPLAY_REJECTED, userId, factor.id());
            throw AuthenticatorErrors.otpInvalid();
        }
    }

    private void verifyRecovery(String userId, String code) {
        if (!store.consumeRecoveryCode(userId, recoveryCodes.hash(userId, code))) {
            emit(AuthenticatorEvent.Type.VERIFY_FAILED, userId, null);
            throw AuthenticatorErrors.otpInvalid();
        }
        emit(AuthenticatorEvent.Type.RECOVERY_CODE_USED, userId, null);
    }

    private MfaChallenge activeChallenge(String mfaToken) {
        if (mfaToken == null || mfaToken.isBlank() || mfaToken.length() > MAX_TOKEN_LENGTH) {
            throw AuthenticatorErrors.tokenExpired();
        }
        String id = Tokens.sha256Hex(mfaToken);
        MfaChallenge challenge = store.findChallenge(id).orElseThrow(AuthenticatorErrors::tokenExpired);
        if (challenge.isExpired(clock.instant())) {
            store.deleteChallenge(id);
            throw AuthenticatorErrors.tokenExpired();
        }
        return challenge;
    }

    private Optional<MfaFactor> totpFactor(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return store.findFactors(userId).stream().filter(factor -> factor.type() == FactorType.TOTP).findFirst();
    }

    private List<String> issueRecoveryCodes(String userId) {
        List<String> codes = recoveryCodes.generate(settings.recoveryCodeCount());
        List<String> hashes = new ArrayList<>(codes.size());
        for (String code : codes) {
            hashes.add(recoveryCodes.hash(userId, code));
        }
        store.replaceRecoveryCodes(userId, hashes);
        return codes;
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw AuthenticatorErrors.unauthorized();
        }
    }

    private static void requireCode(String code) {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw AuthenticatorErrors.otpInvalid();
        }
    }

    private static String secretContext(String userId) {
        return "naqqa-authenticator:totp:" + userId;
    }

    private void emit(AuthenticatorEvent.Type type, String userId, String factorId) {
        try {
            events.onEvent(new AuthenticatorEvent(type, userId, factorId, clock.instant()));
        } catch (RuntimeException ignored) {
            return;
        }
    }
}
