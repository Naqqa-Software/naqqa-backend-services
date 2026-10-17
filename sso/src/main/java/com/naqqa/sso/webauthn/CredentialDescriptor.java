package com.naqqa.sso.webauthn;

import com.naqqa.sso.util.Bytes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CredentialDescriptor(byte[] id, List<String> transports) {
    public CredentialDescriptor {
        id = id.clone();
        transports = transports == null ? List.of() : List.copyOf(transports);
    }

    @Override
    public byte[] id() {
        return id.clone();
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "public-key");
        m.put("id", Bytes.b64url(id));
        if (!transports.isEmpty()) {
            m.put("transports", transports);
        }
        return m;
    }
}
