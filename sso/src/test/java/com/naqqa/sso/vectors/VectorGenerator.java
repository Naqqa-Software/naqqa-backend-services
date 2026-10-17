package com.naqqa.sso.vectors;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class VectorGenerator {
    static final String[] FILES = {"jwt.json", "jwks.json", "totp.json", "pkce.json", "webauthn.json", "api-token.json", "password.json"};

    private VectorGenerator() {
    }

    static Map<String, String> generate(Path dir) {
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (String f : FILES) {
            existing.put(f, VectorIo.load(dir.resolve(f)));
        }
        JwtVectors jwt = new JwtVectors(existing.get("jwt.json"));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("jwt.json", VectorIo.pretty(jwt.build()));
        out.put("jwks.json", VectorIo.pretty(JwksVectors.build(jwt)));
        out.put("totp.json", VectorIo.pretty(TotpVectors.build()));
        out.put("pkce.json", VectorIo.pretty(PkceVectors.build()));
        out.put("webauthn.json", VectorIo.pretty(new WebauthnVectors(existing.get("webauthn.json")).build()));
        out.put("api-token.json", VectorIo.pretty(ApiTokenVectors.build()));
        out.put("password.json", VectorIo.pretty(new PasswordVectors(existing.get("password.json")).build()));
        return out;
    }

    public static void main(String[] args) {
        Path dir = args.length > 0 ? Path.of(args[0]) : VectorIo.dir();
        generate(dir).forEach((name, text) -> VectorIo.write(dir.resolve(name), text));
    }
}
