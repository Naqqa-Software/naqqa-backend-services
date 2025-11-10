package com.naqqa.auth.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest {
    private String email;
    private String oldPassword;
    private String newPassword;
}
