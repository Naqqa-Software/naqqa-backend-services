package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.SsoException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record RegistrationResponse(byte[] credentialId, byte[] clientDataJSON, byte[] attestationObject,
                                   List<String> transports, String binding) {
    public RegistrationResponse {
        if (clientDataJSON == null || attestationObject == null) {
            throw new SsoException("webauthn.response.invalid");
        }
        credentialId = credentialId == null ? null : credentialId.clone();
        clientDataJSON = clientDataJSON.clone();
        attestationObject = attestationObject.clone();
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    @Override
    public byte[] credentialId() {
        return credentialId == null ? null : credentialId.clone();
    }

    @Override
    public byte[] clientDataJSON() {
        return clientDataJSON.clone();
    }

    @Override
    public byte[] attestationObject() {
        return attestationObject.clone();
    }

    public static RegistrationResponse fromJson(String json, String binding) {
        Map<String, Object> m = Json.parseObject(json, WebAuthnLimits.JSON);
        ResponseJson.requireType(m);
        byte[] rawId = ResponseJson.credentialId(m);
        Map<String, Object> r = ResponseJson.response(m);
        List<String> transports = new ArrayList<>();
        if (r.get("transports") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof String s) || s.length() > 32 || transports.size() >= 8) {
                    throw new SsoException("webauthn.response.invalid");
                }
                transports.add(s);
            }
        }
        return new RegistrationResponse(rawId, ResponseJson.b64(r, "clientDataJSON", true),
                ResponseJson.b64(r, "attestationObject", true), transports, binding);
    }
}
