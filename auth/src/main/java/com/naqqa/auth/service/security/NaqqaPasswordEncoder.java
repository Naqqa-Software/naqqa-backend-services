package com.naqqa.auth.service.security;

import org.springframework.security.crypto.password.PasswordEncoder;

public class NaqqaPasswordEncoder implements PasswordEncoder {

    private final PasswordService passwordService;

    public NaqqaPasswordEncoder(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return passwordService.hash(rawPassword == null ? null : rawPassword.toString());
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return passwordService.matches(rawPassword == null ? null : rawPassword.toString(), encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return passwordService.needsRehash(encodedPassword);
    }
}
