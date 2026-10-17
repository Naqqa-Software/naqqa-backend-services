package com.naqqa.sso.webauthn;

public record AuthenticationRequest(String userId, String binding) {
    public static AuthenticationRequest discoverable(String binding) {
        return new AuthenticationRequest(null, binding);
    }
}
