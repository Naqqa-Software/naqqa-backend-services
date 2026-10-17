package com.naqqa.sso.webauthn;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.util.Bytes;
import com.naqqa.sso.util.Hashes;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WebAuthnServer {
    public static final int CHALLENGE_BYTES = 32;
    public static final int USER_HANDLE_BYTES = 64;

    private final WebAuthnPolicy policy;
    private final CredentialStore credentials;
    private final ChallengeStore challenges;
    private final Clock clock;
    private final AttestationVerifier attestationVerifier;
    private final byte[] rpIdHash;

    public WebAuthnServer(WebAuthnPolicy policy, CredentialStore credentials, ChallengeStore challenges, Clock clock) {
        if (policy == null || credentials == null || challenges == null || clock == null) {
            throw new SsoException("webauthn.config.invalid");
        }
        this.policy = policy;
        this.credentials = credentials;
        this.challenges = challenges;
        this.clock = clock;
        this.attestationVerifier = new AttestationVerifier(policy, clock);
        this.rpIdHash = Hashes.sha256(Bytes.utf8(policy.rpId()));
    }

    public WebAuthnPolicy policy() {
        return policy;
    }

    public static byte[] newUserHandle() {
        return Bytes.random(USER_HANDLE_BYTES);
    }

    private long timeoutMillis() {
        return Math.min(policy.timeout().toMillis(), policy.challengeTtl().toMillis());
    }

    private static List<CredentialDescriptor> descriptors(List<CredentialRecord> records) {
        List<CredentialDescriptor> out = new ArrayList<>();
        for (CredentialRecord r : records) {
            out.add(new CredentialDescriptor(r.credentialId(), r.transports()));
        }
        return out;
    }

    public RegistrationOptions startRegistration(RegistrationRequest request) {
        List<CredentialRecord> existing = credentials.findByUserId(request.userId());
        if (existing.size() >= policy.maxCredentialsPerUser()) {
            throw new SsoException("webauthn.max_credentials");
        }
        byte[] handle = request.userHandle();
        if (handle == null) {
            handle = existing.isEmpty() ? newUserHandle() : existing.get(0).userHandle();
        } else {
            for (CredentialRecord r : existing) {
                if (!Bytes.constantTimeEquals(r.userHandle(), handle)) {
                    throw new SsoException("webauthn.user_handle_mismatch");
                }
            }
        }
        byte[] challenge = Bytes.random(CHALLENGE_BYTES);
        Instant expires = clock.instant().plus(policy.challengeTtl());
        challenges.save(new WebAuthnChallenge(challenge, Ceremony.REGISTRATION, handle, request.userId(),
                request.userName(), request.userDisplayName(), request.binding(), expires));
        return new RegistrationOptions(challenge, policy.rpId(), policy.rpName(), handle, request.userName(),
                request.userDisplayName(), policy.allowedAlgorithms(), descriptors(existing), policy.attachment(),
                policy.residentKey(), policy.userVerification(), policy.attestation(), timeoutMillis(), policy.hints());
    }

    public AuthenticationOptions startAuthentication(AuthenticationRequest request) {
        List<CredentialDescriptor> allow = List.of();
        if (request.userId() != null) {
            allow = descriptors(credentials.findByUserId(request.userId()));
        }
        byte[] challenge = Bytes.random(CHALLENGE_BYTES);
        Instant expires = clock.instant().plus(policy.challengeTtl());
        challenges.save(new WebAuthnChallenge(challenge, Ceremony.AUTHENTICATION, null, request.userId(), null, null,
                request.binding(), expires));
        return new AuthenticationOptions(challenge, policy.rpId(), timeoutMillis(), policy.userVerification(), allow,
                policy.hints());
    }

    private WebAuthnChallenge checkClientData(ClientData cd, Ceremony ceremony, String expectedType, String binding) {
        WebAuthnChallenge ch = challenges.consume(cd.challenge())
                .orElseThrow(() -> new SsoException("webauthn.challenge_invalid"));
        if (ch.ceremony() != ceremony || !clock.instant().isBefore(ch.expiresAt())
                || !Bytes.constantTimeEquals(ch.value(), cd.challenge())) {
            throw new SsoException("webauthn.challenge_invalid");
        }
        if (ch.binding() != null && !Bytes.constantTimeEquals(ch.binding(), binding)) {
            throw new SsoException("webauthn.binding_mismatch");
        }
        if (!expectedType.equals(cd.type())) {
            throw new SsoException("webauthn.type_mismatch");
        }
        if (!policy.origins().contains(cd.origin())) {
            throw new SsoException("webauthn.origin_mismatch");
        }
        if (cd.crossOrigin()) {
            throw new SsoException("webauthn.cross_origin");
        }
        return ch;
    }

    private void checkAuthData(AuthenticatorData ad) {
        if (!Bytes.constantTimeEquals(ad.rpIdHash(), rpIdHash)) {
            throw new SsoException("webauthn.rp_id_mismatch");
        }
        if (!ad.userPresent()) {
            throw new SsoException("webauthn.user_not_present");
        }
        if (policy.userVerificationRequired() && !ad.userVerified()) {
            throw new SsoException("webauthn.user_not_verified");
        }
    }

    public RegistrationResult finishRegistration(RegistrationResponse response) {
        ClientData cd = ClientData.parse(response.clientDataJSON());
        WebAuthnChallenge ch = checkClientData(cd, Ceremony.REGISTRATION, ClientData.TYPE_CREATE, response.binding());
        Map<Object, Object> att = Cbor.asMap(Cbor.decode(response.attestationObject()), "webauthn.attestation.invalid");
        if (!Set.of("fmt", "attStmt", "authData").containsAll(att.keySet())
                || !(att.get("fmt") instanceof String fmt) || !(att.get("authData") instanceof byte[] authBytes)) {
            throw new SsoException("webauthn.attestation.invalid");
        }
        Map<Object, Object> attStmt = Cbor.asMap(att.get("attStmt"), "webauthn.attestation.invalid");
        AuthenticatorData ad = AuthenticatorData.parse(authBytes);
        checkAuthData(ad);
        AttestedCredentialData acd = ad.attestedCredentialData();
        if (acd == null) {
            throw new SsoException("webauthn.authdata.no_credential");
        }
        if (!policy.allowedAlgorithms().contains(acd.credentialPublicKey().algorithm())) {
            throw new SsoException("webauthn.alg_not_allowed");
        }
        if (response.credentialId() != null && !Bytes.constantTimeEquals(response.credentialId(), acd.credentialId())) {
            throw new SsoException("webauthn.credential_id_mismatch");
        }
        AttestationResult attestation = attestationVerifier.verify(fmt, attStmt, ad, cd.hash());
        if (credentials.findById(acd.credentialId()).isPresent()) {
            throw new SsoException("webauthn.credential_exists");
        }
        if (credentials.findByUserId(ch.userId()).size() >= policy.maxCredentialsPerUser()) {
            throw new SsoException("webauthn.max_credentials");
        }
        Instant now = clock.instant();
        CredentialRecord record = new CredentialRecord(acd.credentialId(), ch.userHandle(), ch.userId(),
                acd.credentialPublicKey().encoded(), acd.credentialPublicKey().algorithm(), ad.signCount(),
                ad.userVerified(), ad.backupEligible(), ad.backupState(), response.transports(), acd.aaguid(),
                attestation.format(), attestation.type(), attestation.trusted(), now, null, false);
        credentials.create(record);
        return new RegistrationResult(record, attestation, ad.userVerified());
    }

    public AuthenticationResult finishAuthentication(AuthenticationResponse response) {
        ClientData cd = ClientData.parse(response.clientDataJSON());
        WebAuthnChallenge ch = checkClientData(cd, Ceremony.AUTHENTICATION, ClientData.TYPE_GET, response.binding());
        CredentialRecord stored = credentials.findById(response.credentialId())
                .orElseThrow(() -> new SsoException("webauthn.credential_unknown"));
        if (ch.userId() != null && !ch.userId().equals(stored.userId())) {
            throw new SsoException("webauthn.user_mismatch");
        }
        byte[] handle = response.userHandle();
        if (handle != null && !Bytes.constantTimeEquals(handle, stored.userHandle())) {
            throw new SsoException("webauthn.user_handle_mismatch");
        }
        if (handle == null && ch.userId() == null) {
            throw new SsoException("webauthn.user_handle_missing");
        }
        if (stored.cloneDetected() && policy.failOnClone()) {
            throw new SsoException("webauthn.credential_disabled");
        }
        AuthenticatorData ad = AuthenticatorData.parse(response.authenticatorData());
        if (ad.hasAttestedCredentialData()) {
            throw new SsoException("webauthn.authdata.unexpected_credential");
        }
        checkAuthData(ad);
        if (ad.backupEligible() != stored.backupEligible()) {
            throw new SsoException("webauthn.backup_eligibility_changed");
        }
        CoseKey key = stored.publicKey();
        if (!key.verify(Bytes.concat(response.authenticatorData(), cd.hash()), response.signature())) {
            throw new SsoException("webauthn.signature_invalid");
        }
        long newCount = ad.signCount();
        long oldCount = stored.signCount();
        boolean clone = (newCount != 0 || oldCount != 0) && newCount <= oldCount;
        CredentialRecord base = stored;
        if (clone) {
            CredentialRecord flagged = stored.withCloneDetected();
            credentials.update(stored, flagged);
            if (policy.failOnClone()) {
                throw new SsoException("webauthn.clone_detected");
            }
            base = flagged;
        }
        CredentialRecord updated = base.withUsage(Math.max(newCount, oldCount), ad.backupState(), ad.userVerified(), clock.instant());
        if (!credentials.update(base, updated)) {
            throw new SsoException("webauthn.concurrent_update");
        }
        return new AuthenticationResult(updated, updated.userId(), ad.userVerified(), clone, ad.backupState());
    }
}
