package com.naqqa.elasticsearch.security.authc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

public final class BasicAuthHeader {

    private BasicAuthHeader() {
    }

    public static Optional<UsernamePasswordToken> parse(String headerValue) {
        if (headerValue == null || !headerValue.startsWith("Basic ")) {
            return Optional.empty();
        }
        String encoded = headerValue.substring("Basic ".length()).trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            String decodedString = new String(decoded, StandardCharsets.UTF_8);
            int idx = decodedString.indexOf(':');
            if (idx < 0) {
                return Optional.empty();
            }
            String username = decodedString.substring(0, idx);
            String password = decodedString.substring(idx + 1);
            return Optional.of(new UsernamePasswordToken(username, password.toCharArray()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
