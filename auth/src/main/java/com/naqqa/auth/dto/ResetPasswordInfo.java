package com.naqqa.auth.dto;

public record ResetPasswordInfo(
        String uuid,
        String email
) {}
