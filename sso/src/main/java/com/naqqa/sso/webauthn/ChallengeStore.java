package com.naqqa.sso.webauthn;

import java.util.Optional;

public interface ChallengeStore {
    void save(WebAuthnChallenge challenge);

    Optional<WebAuthnChallenge> consume(byte[] value);
}
