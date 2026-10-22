package com.naqqa.authenticator.spi;

import com.naqqa.authenticator.domain.MfaChallenge;
import com.naqqa.authenticator.domain.MfaEnrollment;
import com.naqqa.authenticator.domain.MfaFactor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MfaStore {

    List<MfaFactor> findFactors(String userId);

    Optional<MfaFactor> findFactor(String userId, String factorId);

    void saveFactor(MfaFactor factor);

    boolean deleteFactor(String userId, String factorId);

    void deleteFactors(String userId);

    boolean recordUse(String factorId, long counter, Instant usedAt);

    void saveEnrollment(MfaEnrollment enrollment);

    Optional<MfaEnrollment> findEnrollment(String enrollmentId);

    void deleteEnrollment(String enrollmentId);

    void deleteEnrollments(String userId);

    void saveChallenge(MfaChallenge challenge);

    Optional<MfaChallenge> findChallenge(String challengeId);

    int incrementAttempts(String challengeId);

    boolean deleteChallenge(String challengeId);

    void deleteChallenges(String userId);

    void replaceRecoveryCodes(String userId, List<String> codeHashes);

    boolean consumeRecoveryCode(String userId, String codeHash);

    int countRecoveryCodes(String userId);

    void deleteRecoveryCodes(String userId);
}
