package com.naqqa.elasticsearch.security.authc;

import java.util.List;

public final class PasswordHashers {

    private static final List<PasswordHasher> ALL = List.of(new Pbkdf2PasswordHasher(), new Ssha256PasswordHasher());

    private PasswordHashers() {
    }

    public static String hashWithPbkdf2(char[] password) {
        return new Pbkdf2PasswordHasher().hash(password);
    }

    public static String hashWithSsha256(char[] password) {
        return new Ssha256PasswordHasher().hash(password);
    }

    public static boolean verify(char[] password, String hash) {
        for (PasswordHasher hasher : ALL) {
            if (hasher.canHandle(hash)) {
                return hasher.verify(password, hash);
            }
        }
        return false;
    }
}
