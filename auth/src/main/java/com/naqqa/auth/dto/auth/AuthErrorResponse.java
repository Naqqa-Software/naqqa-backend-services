package com.naqqa.auth.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthErrorResponse(
        String message,
        String uuid
) {}
