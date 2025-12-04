package com.naqqa.auth.dto.auth;

import lombok.Data;

@Data
public class EmailConfirmationRequest {
    String uuid;
    String code;
}
