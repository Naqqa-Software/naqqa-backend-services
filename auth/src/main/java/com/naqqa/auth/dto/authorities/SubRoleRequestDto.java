package com.naqqa.auth.dto.authorities;

import lombok.Data;
import java.util.Set;

@Data
public class SubRoleRequestDto {
    private String name;
    private Set<String> authorityNames;
}