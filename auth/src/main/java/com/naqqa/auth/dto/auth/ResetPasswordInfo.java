package com.naqqa.auth.dto.auth;

public record ResetPasswordInfo(
        String uuid,
        String email
) {}
