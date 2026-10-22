package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

public record WebAuthnPolicy(String rpId, String rpName, List<String> origins, UserVerification userVerification,
                             AttestationConveyance attestation, List<CoseAlgorithm> allowedAlgorithms,
                             ResidentKey residentKey, AuthenticatorAttachment attachment,
                             List<PublicKeyCredentialHint> hints, boolean allowNoneAttestation,
                             boolean allowUnverifiedAttestation, boolean requireTrustedAttestation,
                             List<X509Certificate> trustAnchors, int maxCredentialsPerUser, Duration timeout,
                             Duration challengeTtl, boolean failOnClone) {
    public static final List<CoseAlgorithm> DEFAULT_ALGORITHMS = List.of(CoseAlgorithm.EDDSA, CoseAlgorithm.ES256, CoseAlgorithm.RS256);
    public static final Duration MAX_CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final String ANDROID_PREFIX = "android:apk-key-hash:";

    public WebAuthnPolicy {
        if (rpId == null || rpId.isBlank() || !rpId.equals(rpId.toLowerCase(Locale.ROOT)) || rpId.contains("/")
                || rpId.contains(":") || rpId.startsWith(".") || rpId.endsWith(".")) {
            throw new SsoException("webauthn.policy.rp_id");
        }
        if (rpName == null || rpName.isBlank()) {
            throw new SsoException("webauthn.policy.rp_name");
        }
        if (origins == null || origins.isEmpty()) {
            throw new SsoException("webauthn.policy.origins");
        }
        for (String o : origins) {
            validateOrigin(rpId, o);
        }
        origins = List.copyOf(origins);
        if (userVerification == null || attestation == null || residentKey == null) {
            throw new SsoException("webauthn.policy.invalid");
        }
        if (allowedAlgorithms == null || allowedAlgorithms.isEmpty()) {
            throw new SsoException("webauthn.policy.algorithms");
        }
        allowedAlgorithms = List.copyOf(allowedAlgorithms);
        hints = hints == null ? List.of() : List.copyOf(hints);
        trustAnchors = trustAnchors == null ? List.of() : List.copyOf(trustAnchors);
        if (requireTrustedAttestation && (trustAnchors.isEmpty() || attestation == AttestationConveyance.NONE)) {
            throw new SsoException("webauthn.policy.trust_anchors");
        }
        if (maxCredentialsPerUser < 1 || maxCredentialsPerUser > 100) {
            throw new SsoException("webauthn.policy.max_credentials");
        }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(30)) < 0 || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new SsoException("webauthn.policy.timeout");
        }
        if (challengeTtl == null || challengeTtl.isNegative() || challengeTtl.isZero() || challengeTtl.compareTo(MAX_CHALLENGE_TTL) > 0) {
            throw new SsoException("webauthn.policy.challenge_ttl");
        }
    }

    private static void validateOrigin(String rpId, String origin) {
        if (origin == null || origin.isBlank()) {
            throw new SsoException("webauthn.policy.origins");
        }
        if (origin.startsWith(ANDROID_PREFIX)) {
            if (origin.length() == ANDROID_PREFIX.length()) {
                throw new SsoException("webauthn.policy.origins");
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (Exception e) {
            throw new SsoException("webauthn.policy.origins");
        }
        String host = uri.getHost();
        if (host == null || uri.getRawPath() != null && !uri.getRawPath().isEmpty() || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null || !host.equals(host.toLowerCase(Locale.ROOT))) {
            throw new SsoException("webauthn.policy.origins");
        }
        boolean localhost = "localhost".equals(host) && "localhost".equals(rpId);
        if (!"https".equals(uri.getScheme()) && !(localhost && "http".equals(uri.getScheme()))) {
            throw new SsoException("webauthn.policy.origins");
        }
        if (!host.equals(rpId) && !host.endsWith("." + rpId)) {
            throw new SsoException("webauthn.policy.origins");
        }
    }

    public boolean userVerificationRequired() {
        return userVerification == UserVerification.REQUIRED;
    }

    public Builder toBuilder() {
        Builder b = new Builder(rpId, rpName);
        b.origins = origins;
        b.userVerification = userVerification;
        b.attestation = attestation;
        b.allowedAlgorithms = allowedAlgorithms;
        b.residentKey = residentKey;
        b.attachment = attachment;
        b.hints = hints;
        b.allowNoneAttestation = allowNoneAttestation;
        b.allowUnverifiedAttestation = allowUnverifiedAttestation;
        b.requireTrustedAttestation = requireTrustedAttestation;
        b.trustAnchors = trustAnchors;
        b.maxCredentialsPerUser = maxCredentialsPerUser;
        b.timeout = timeout;
        b.challengeTtl = challengeTtl;
        b.failOnClone = failOnClone;
        return b;
    }

    public static Builder builder(String rpId, String rpName) {
        return new Builder(rpId, rpName);
    }

    public static final class Builder {
        private final String rpId;
        private final String rpName;
        private List<String> origins = List.of();
        private UserVerification userVerification = UserVerification.PREFERRED;
        private AttestationConveyance attestation = AttestationConveyance.NONE;
        private List<CoseAlgorithm> allowedAlgorithms = DEFAULT_ALGORITHMS;
        private ResidentKey residentKey = ResidentKey.PREFERRED;
        private AuthenticatorAttachment attachment;
        private List<PublicKeyCredentialHint> hints = List.of();
        private boolean allowNoneAttestation = true;
        private boolean allowUnverifiedAttestation;
        private boolean requireTrustedAttestation;
        private List<X509Certificate> trustAnchors = List.of();
        private int maxCredentialsPerUser = 10;
        private Duration timeout = Duration.ofMinutes(5);
        private Duration challengeTtl = MAX_CHALLENGE_TTL;
        private boolean failOnClone = true;

        private Builder(String rpId, String rpName) {
            this.rpId = rpId;
            this.rpName = rpName;
        }

        public Builder origins(List<String> v) {
            origins = v;
            return this;
        }

        public Builder userVerification(UserVerification v) {
            userVerification = v;
            return this;
        }

        public Builder attestation(AttestationConveyance v) {
            attestation = v;
            return this;
        }

        public Builder allowedAlgorithms(List<CoseAlgorithm> v) {
            allowedAlgorithms = v;
            return this;
        }

        public Builder residentKey(ResidentKey v) {
            residentKey = v;
            return this;
        }

        public Builder attachment(AuthenticatorAttachment v) {
            attachment = v;
            return this;
        }

        public Builder hints(List<PublicKeyCredentialHint> v) {
            hints = v;
            return this;
        }

        public Builder allowNoneAttestation(boolean v) {
            allowNoneAttestation = v;
            return this;
        }

        public Builder allowUnverifiedAttestation(boolean v) {
            allowUnverifiedAttestation = v;
            return this;
        }

        public Builder requireTrustedAttestation(boolean v) {
            requireTrustedAttestation = v;
            return this;
        }

        public Builder trustAnchors(List<X509Certificate> v) {
            trustAnchors = v;
            return this;
        }

        public Builder maxCredentialsPerUser(int v) {
            maxCredentialsPerUser = v;
            return this;
        }

        public Builder timeout(Duration v) {
            timeout = v;
            return this;
        }

        public Builder challengeTtl(Duration v) {
            challengeTtl = v;
            return this;
        }

        public Builder failOnClone(boolean v) {
            failOnClone = v;
            return this;
        }

        public WebAuthnPolicy build() {
            return new WebAuthnPolicy(rpId, rpName, origins, userVerification, attestation, allowedAlgorithms,
                    residentKey, attachment, hints, allowNoneAttestation, allowUnverifiedAttestation,
                    requireTrustedAttestation, trustAnchors, maxCredentialsPerUser, timeout, challengeTtl, failOnClone);
        }
    }
}
