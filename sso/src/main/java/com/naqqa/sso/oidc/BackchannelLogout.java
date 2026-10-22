package com.naqqa.sso.oidc;

public record BackchannelLogout(String clientId, String uri, String logoutToken) {
}
