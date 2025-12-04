package com.naqqa.auth.dto.authorities;

import lombok.Data;

import java.util.Set;

@Data
public class RoleRequestDto {
    private String name;
    private Set<String> authorityNames;
}