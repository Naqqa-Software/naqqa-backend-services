package com.naqqa.auth.dto.admin;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateUserRequest {
    private String email;
    private String password;
    private String fullName;
    private String roleName;
}
