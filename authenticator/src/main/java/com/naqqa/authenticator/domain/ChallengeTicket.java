package com.naqqa.authenticator.domain;

import java.time.Instant;
import java.util.List;

public record ChallengeTicket(String mfaToken, List<String> methods, Instant expiresAt) {

    public ChallengeTicket {
        methods = List.copyOf(methods);
    }
}
