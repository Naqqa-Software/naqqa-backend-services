package com.naqqa.auth.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class AdminUserResponse {
    private Long id;
    private String email;
    private String fullName;
    private boolean enabled;
    private Set<String> roles;
    private Set<String> subRoles;
}
