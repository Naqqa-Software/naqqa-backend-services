package com.naqqa.sso.saml.idp;

public record LogoutResponseInfo(String id, String inResponseTo, String spEntityId, String statusCode, String relayState) {
    public boolean success() {
        return com.naqqa.sso.saml.Saml.STATUS_SUCCESS.equals(statusCode);
    }
}
