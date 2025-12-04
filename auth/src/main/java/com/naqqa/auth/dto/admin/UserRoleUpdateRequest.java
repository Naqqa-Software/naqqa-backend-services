package com.naqqa.auth.dto.admin;

import lombok.Data;

import java.util.Set;

@Data
public class UserRoleUpdateRequest {
    private Set<String> roleNames;
}
