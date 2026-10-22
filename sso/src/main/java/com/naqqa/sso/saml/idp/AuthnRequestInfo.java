package com.naqqa.sso.saml.idp;

import java.time.Instant;
import java.util.List;

public record AuthnRequestInfo(String id, String spEntityId, String assertionConsumerServiceUrl, String relayState,
                               String nameIdFormat, boolean forceAuthn, boolean isPassive,
                               List<String> requestedAuthnContexts, Instant issueInstant, boolean signed) {
    public AuthnRequestInfo {
        requestedAuthnContexts = requestedAuthnContexts == null ? List.of() : List.copyOf(requestedAuthnContexts);
    }
}
