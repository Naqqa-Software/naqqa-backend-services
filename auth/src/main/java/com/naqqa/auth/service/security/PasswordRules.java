package com.naqqa.auth.service.security;

import com.naqqa.auth.config.auth.Errors;
import com.naqqa.auth.exceptions.BadRequestException;

import java.util.Locale;
import java.util.Set;

public final class PasswordRules {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    static final Set<String> COMMON = Set.of(
            "password", "password1", "password12", "password123", "passw0rd", "p@ssw0rd", "p@ssword",
            "12345678", "123456789", "1234567890", "0123456789", "87654321", "12341234", "12344321",
            "11111111", "00000000", "88888888", "66666666", "99999999", "12121212", "11223344", "123123123",
            "qwerty12", "qwerty123", "qwerty1234", "qwertyui", "qwertyuiop", "1q2w3e4r", "1q2w3e4r5t", "q1w2e3r4",
            "1qaz2wsx", "zaq12wsx", "qazwsxedc", "asdfghjk", "asdfghjkl", "1234qwer", "abc12345", "abcd1234",
            "aa123456", "iloveyou", "sunshine", "princess", "football", "baseball", "superman", "trustno1",
            "welcome1", "welcome123", "admin123", "administrator", "letmein1", "michael1", "computer",
            "parola123", "parola12", "parola1234");

    private PasswordRules() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES
                || COMMON.contains(password.trim().toLowerCase(Locale.ROOT))) {
            throw new BadRequestException(Errors.REG_PASSWORD_WEAK);
        }
    }
}
