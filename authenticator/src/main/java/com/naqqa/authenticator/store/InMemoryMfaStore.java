package com.naqqa.authenticator.store;

import com.naqqa.authenticator.domain.MfaChallenge;
import com.naqqa.authenticator.domain.MfaEnrollment;
import com.naqqa.authenticator.domain.MfaFactor;
import com.naqqa.authenticator.spi.MfaStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryMfaStore implements MfaStore {

    private final Map<String, MfaFactor> factors = new LinkedHashMap<>();
    private final Map<String, MfaEnrollment> enrollments = new HashMap<>();
    private final Map<String, MfaChallenge> challenges = new HashMap<>();
    private final Map<String, List<String>> recoveryCodes = new HashMap<>();

    @Override
    public synchronized List<MfaFactor> findFactors(String userId) {
        List<MfaFactor> result = new ArrayList<>();
        for (MfaFactor factor : factors.values()) {
            if (factor.userId().equals(userId)) {
                result.add(factor);
            }
        }
        result.sort(Comparator.comparing(MfaFactor::createdAt));
        return result;
    }

    @Override
    public synchronized Optional<MfaFactor> findFactor(String userId, String factorId) {
        MfaFactor factor = factors.get(factorId);
        return factor != null && factor.userId().equals(userId) ? Optional.of(factor) : Optional.empty();
    }

    @Override
    public synchronized void saveFactor(MfaFactor factor) {
        factors.put(factor.id(), factor);
    }

    @Override
    public synchronized boolean deleteFactor(String userId, String factorId) {
        MfaFactor factor = factors.get(factorId);
        if (factor == null || !factor.userId().equals(userId)) {
            return false;
        }
        factors.remove(factorId);
        return true;
    }

    @Override
    public synchronized void deleteFactors(String userId) {
        factors.values().removeIf(factor -> factor.userId().equals(userId));
    }

    @Override
    public synchronized boolean recordUse(String factorId, long counter, Instant usedAt) {
        MfaFactor factor = factors.get(factorId);
        if (factor == null || counter <= factor.lastUsedCounter()) {
            return false;
        }
        factors.put(factorId, factor.withUse(counter, usedAt));
        return true;
    }

    @Override
    public synchronized void saveEnrollment(MfaEnrollment enrollment) {
        enrollments.put(enrollment.id(), enrollment);
    }

    @Override
    public synchronized Optional<MfaEnrollment> findEnrollment(String enrollmentId) {
        return Optional.ofNullable(enrollments.get(enrollmentId));
    }

    @Override
    public synchronized void deleteEnrollment(String enrollmentId) {
        enrollments.remove(enrollmentId);
    }

    @Override
    public synchronized void deleteEnrollments(String userId) {
        enrollments.values().removeIf(enrollment -> enrollment.userId().equals(userId));
    }

    @Override
    public synchronized void saveChallenge(MfaChallenge challenge) {
        challenges.put(challenge.id(), challenge);
    }

    @Override
    public synchronized Optional<MfaChallenge> findChallenge(String challengeId) {
        return Optional.ofNullable(challenges.get(challengeId));
    }

    @Override
    public synchronized int incrementAttempts(String challengeId) {
        MfaChallenge challenge = challenges.get(challengeId);
        if (challenge == null) {
            return Integer.MAX_VALUE;
        }
        MfaChallenge updated = new MfaChallenge(challenge.id(), challenge.userId(), challenge.context(),
                challenge.attempts() + 1, challenge.createdAt(), challenge.expiresAt());
        challenges.put(challengeId, updated);
        return updated.attempts();
    }

    @Override
    public synchronized boolean deleteChallenge(String challengeId) {
        return challenges.remove(challengeId) != null;
    }

    @Override
    public synchronized void deleteChallenges(String userId) {
        challenges.values().removeIf(challenge -> challenge.userId().equals(userId));
    }

    @Override
    public synchronized void replaceRecoveryCodes(String userId, List<String> codeHashes) {
        recoveryCodes.put(userId, new ArrayList<>(codeHashes));
    }

    @Override
    public synchronized boolean consumeRecoveryCode(String userId, String codeHash) {
        List<String> codes = recoveryCodes.get(userId);
        return codes != null && codes.remove(codeHash);
    }

    @Override
    public synchronized int countRecoveryCodes(String userId) {
        List<String> codes = recoveryCodes.get(userId);
        return codes == null ? 0 : codes.size();
    }

    @Override
    public synchronized void deleteRecoveryCodes(String userId) {
        recoveryCodes.remove(userId);
    }
}
