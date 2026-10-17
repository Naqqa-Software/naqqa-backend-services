package com.naqqa.sso.tokens;

import com.naqqa.sso.SsoException;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

public record ApiToken(ApiTokenKind kind, ApiTokenEnv env, String id, String secret) {
    public static final String PREFIX = "sso";
    public static final int ID_LENGTH = 16;
    public static final int SECRET_LENGTH = 43;
    public static final int CRC_LENGTH = 6;
    public static final int TOKEN_LENGTH = 4 + 3 + 5 + ID_LENGTH + 1 + SECRET_LENGTH + 1 + CRC_LENGTH;

    public ApiToken {
        if (kind == null || env == null || !isValidId(id) || secret == null || secret.length() != SECRET_LENGTH || !Base62.isBase62(secret)) {
            throw new SsoException("token.malformed");
        }
    }

    public static boolean isValidId(String id) {
        return id != null && id.length() == ID_LENGTH && Base62.isBase62(id);
    }

    public static ApiToken generate(ApiTokenKind kind, ApiTokenEnv env) {
        return new ApiToken(kind, env, Base62.random(ID_LENGTH), Base62.random(SECRET_LENGTH));
    }

    public ApiToken withNewSecret() {
        return new ApiToken(kind, env, id, Base62.random(SECRET_LENGTH));
    }

    public String encode() {
        String body = body(kind, env, id, secret);
        return body + "_" + checksum(body);
    }

    public static ApiToken parse(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) {
            throw new SsoException("token.malformed");
        }
        String[] parts = token.split("_", -1);
        if (parts.length != 6 || !PREFIX.equals(parts[0])) {
            throw new SsoException("token.malformed");
        }
        ApiTokenKind kind = ApiTokenKind.fromCode(parts[1]);
        ApiTokenEnv env = ApiTokenEnv.fromCode(parts[2]);
        String crc = parts[5];
        if (crc.length() != CRC_LENGTH || !Base62.isBase62(crc)) {
            throw new SsoException("token.malformed");
        }
        ApiToken t = new ApiToken(kind, env, parts[3], parts[4]);
        if (!checksum(body(kind, env, parts[3], parts[4])).equals(crc)) {
            throw new SsoException("token.checksum");
        }
        return t;
    }

    private static String body(ApiTokenKind kind, ApiTokenEnv env, String id, String secret) {
        return PREFIX + "_" + kind.code() + "_" + env.code() + "_" + id + "_" + secret;
    }

    static String checksum(String body) {
        CRC32 crc = new CRC32();
        crc.update(body.getBytes(StandardCharsets.US_ASCII));
        return Base62.encodeFixed(crc.getValue(), CRC_LENGTH);
    }

    @Override
    public String toString() {
        return "ApiToken[" + PREFIX + "_" + kind.code() + "_" + env.code() + "_" + id + "_***]";
    }
}
