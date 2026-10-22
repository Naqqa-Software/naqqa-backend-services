package com.naqqa.authenticator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "naqqa.authenticator")
public class AuthenticatorProperties {

    private boolean enabled = true;
    private String path = "/api/auth/mfa";
    private String issuer = "naqqa";
    private String factorLabel = "Authenticator app";
    private String algorithm = "SHA1";
    private int digits = 6;
    private int periodSeconds = 30;
    private int skewSteps = 1;
    private int secretBytes = 20;
    private Duration enrollmentTtl = Duration.ofMinutes(10);
    private Duration challengeTtl = Duration.ofMinutes(5);
    private int maxAttempts = 5;
    private int recoveryCodeCount = 10;
    private String encryptionKey;
    private String collectionPrefix = "naqqa_mfa_";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getFactorLabel() {
        return factorLabel;
    }

    public void setFactorLabel(String factorLabel) {
        this.factorLabel = factorLabel;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public int getDigits() {
        return digits;
    }

    public void setDigits(int digits) {
        this.digits = digits;
    }

    public int getPeriodSeconds() {
        return periodSeconds;
    }

    public void setPeriodSeconds(int periodSeconds) {
        this.periodSeconds = periodSeconds;
    }

    public int getSkewSteps() {
        return skewSteps;
    }

    public void setSkewSteps(int skewSteps) {
        this.skewSteps = skewSteps;
    }

    public int getSecretBytes() {
        return secretBytes;
    }

    public void setSecretBytes(int secretBytes) {
        this.secretBytes = secretBytes;
    }

    public Duration getEnrollmentTtl() {
        return enrollmentTtl;
    }

    public void setEnrollmentTtl(Duration enrollmentTtl) {
        this.enrollmentTtl = enrollmentTtl;
    }

    public Duration getChallengeTtl() {
        return challengeTtl;
    }

    public void setChallengeTtl(Duration challengeTtl) {
        this.challengeTtl = challengeTtl;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getRecoveryCodeCount() {
        return recoveryCodeCount;
    }

    public void setRecoveryCodeCount(int recoveryCodeCount) {
        this.recoveryCodeCount = recoveryCodeCount;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getCollectionPrefix() {
        return collectionPrefix;
    }

    public void setCollectionPrefix(String collectionPrefix) {
        this.collectionPrefix = collectionPrefix;
    }
}
