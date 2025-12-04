package com.naqqa.auth.dto.auth;

public record ResetPasswordRequest(
        String email,
        String uuid,
        String password) {}
