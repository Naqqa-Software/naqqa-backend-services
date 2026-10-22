package com.naqqa.sso.webauthn;

import com.naqqa.sso.json.Json;
import com.naqqa.sso.SsoException;

import java.util.Map;

public record AuthenticationResponse(byte[] credentialId, byte[] clientDataJSON, byte[] authenticatorData,
                                     byte[] signature, byte[] userHandle, String binding) {
    public AuthenticationResponse {
        if (credentialId == null || clientDataJSON == null || authenticatorData == null || signature == null) {
            throw new SsoException("webauthn.response.invalid");
        }
        credentialId = credentialId.clone();
        clientDataJSON = clientDataJSON.clone();
        authenticatorData = authenticatorData.clone();
        signature = signature.clone();
        userHandle = userHandle == null || userHandle.length == 0 ? null : userHandle.clone();
    }

    @Override
    public byte[] credentialId() {
        return credentialId.clone();
    }

    @Override
    public byte[] clientDataJSON() {
        return clientDataJSON.clone();
    }

    @Override
    public byte[] authenticatorData() {
        return authenticatorData.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    @Override
    public byte[] userHandle() {
        return userHandle == null ? null : userHandle.clone();
    }

    public static AuthenticationResponse fromJson(String json, String binding) {
        Map<String, Object> m = Json.parseObject(json, WebAuthnLimits.JSON);
        ResponseJson.requireType(m);
        byte[] id = ResponseJson.credentialId(m);
        Map<String, Object> r = ResponseJson.response(m);
        return new AuthenticationResponse(id, ResponseJson.b64(r, "clientDataJSON", true),
                ResponseJson.b64(r, "authenticatorData", true), ResponseJson.b64(r, "signature", true),
                ResponseJson.b64(r, "userHandle", false), binding);
    }
}
