package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.util.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RegistrationOptions(byte[] challenge, String rpId, String rpName, byte[] userHandle, String userName,
                                  String userDisplayName, List<CoseAlgorithm> algorithms,
                                  List<CredentialDescriptor> excludeCredentials, AuthenticatorAttachment attachment,
                                  ResidentKey residentKey, UserVerification userVerification,
                                  AttestationConveyance attestation, long timeoutMillis,
                                  List<PublicKeyCredentialHint> hints) {
    public RegistrationOptions {
        challenge = challenge.clone();
        userHandle = userHandle.clone();
        algorithms = List.copyOf(algorithms);
        excludeCredentials = List.copyOf(excludeCredentials);
        hints = List.copyOf(hints);
    }

    @Override
    public byte[] challenge() {
        return challenge.clone();
    }

    @Override
    public byte[] userHandle() {
        return userHandle.clone();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("id", rpId);
        rp.put("name", rpName);
        m.put("rp", rp);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", Bytes.b64url(userHandle));
        user.put("name", userName);
        user.put("displayName", userDisplayName);
        m.put("user", user);
        m.put("challenge", Bytes.b64url(challenge));
        List<Object> params = new ArrayList<>();
        for (CoseAlgorithm a : algorithms) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "public-key");
            p.put("alg", (long) a.id());
            params.add(p);
        }
        m.put("pubKeyCredParams", params);
        m.put("timeout", timeoutMillis);
        List<Object> ex = new ArrayList<>();
        for (CredentialDescriptor d : excludeCredentials) {
            ex.add(d.toMap());
        }
        m.put("excludeCredentials", ex);
        Map<String, Object> sel = new LinkedHashMap<>();
        if (attachment != null) {
            sel.put("authenticatorAttachment", attachment.value());
        }
        sel.put("residentKey", residentKey.value());
        sel.put("requireResidentKey", residentKey == ResidentKey.REQUIRED);
        sel.put("userVerification", userVerification.value());
        m.put("authenticatorSelection", sel);
        List<Object> h = new ArrayList<>();
        for (PublicKeyCredentialHint hint : hints) {
            h.add(hint.value());
        }
        m.put("hints", h);
        m.put("attestation", attestation.value());
        m.put("extensions", Map.of("credProps", Boolean.TRUE));
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }
}
