package com.naqqa.sso.webauthn;

import java.security.cert.X509Certificate;
import java.util.List;

public record AttestationResult(String format, AttestationType type, List<X509Certificate> chain, boolean trusted) {
    public AttestationResult {
        chain = chain == null ? List.of() : List.copyOf(chain);
    }
}
