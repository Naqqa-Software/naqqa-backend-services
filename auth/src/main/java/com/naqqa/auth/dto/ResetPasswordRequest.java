package com.naqqa.auth.dto;

public record ResetPasswordRequest(
        String email,
        String uuid,
        String password) {}
