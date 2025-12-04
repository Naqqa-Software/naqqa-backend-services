package com.naqqa.auth.dto.authorities;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class RoleAuthorityDto {
    private Long id;
    private String name;
    private Set<String> authorityNames; // The list of authorities currently assigned

}