package com.naqqa.auth.dto;

import lombok.Data;

@Data
public class EmailConfirmationRequest {
    String uuid;
    String code;
}
