package com.naqqa.sso.oauth;

public enum ProviderKind {
    GOOGLE(true),
    APPLE(true),
    MICROSOFT(true),
    GITHUB(false),
    FACEBOOK(false),
    GENERIC_OIDC(true);

    private final boolean oidc;

    ProviderKind(boolean oidc) {
        this.oidc = oidc;
    }

    public boolean oidc() {
        return oidc;
    }
}
