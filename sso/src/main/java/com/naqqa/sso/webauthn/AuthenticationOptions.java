package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AuthenticationOptions(byte[] challenge, String rpId, long timeoutMillis, UserVerification userVerification,
                                    List<CredentialDescriptor> allowCredentials, List<PublicKeyCredentialHint> hints) {
    public AuthenticationOptions {
        challenge = challenge.clone();
        allowCredentials = List.copyOf(allowCredentials);
        hints = List.copyOf(hints);
    }

    @Override
    public byte[] challenge() {
        return challenge.clone();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("challenge", Bytes.b64url(challenge));
        m.put("timeout", timeoutMillis);
        m.put("rpId", rpId);
        List<Object> allow = new ArrayList<>();
        for (CredentialDescriptor d : allowCredentials) {
            allow.add(d.toMap());
        }
        m.put("allowCredentials", allow);
        m.put("userVerification", userVerification.value());
        List<Object> h = new ArrayList<>();
        for (PublicKeyCredentialHint hint : hints) {
            h.add(hint.value());
        }
        m.put("hints", h);
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }
}
