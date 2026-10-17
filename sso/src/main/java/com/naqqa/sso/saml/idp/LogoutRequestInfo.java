package com.naqqa.sso.saml.idp;

import java.util.List;

public record LogoutRequestInfo(String id, String spEntityId, String nameId, String nameIdFormat,
                                List<String> sessionIndexes, String relayState) {
    public LogoutRequestInfo {
        sessionIndexes = sessionIndexes == null ? List.of() : List.copyOf(sessionIndexes);
    }
}
