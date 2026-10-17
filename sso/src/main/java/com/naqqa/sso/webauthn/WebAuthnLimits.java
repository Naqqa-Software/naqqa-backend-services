package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.JsonLimits;

final class WebAuthnLimits {
    static final JsonLimits JSON = new JsonLimits(16, 65536);

    private WebAuthnLimits() {
    }
}
